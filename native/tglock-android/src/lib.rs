//! In-process, loopback-only adapter for the pinned TGLock core.
use jni::{objects::JClass, sys::jstring, JNIEnv};
use std::future::Future;
use std::sync::{atomic::Ordering, Arc, Mutex, OnceLock};
use std::task::Poll;
use std::time::Duration;
use tglock_lib::{
    config::ListenConfig,
    proxy::{self, Stats},
};
use tokio::{runtime::Runtime, task::JoinHandle};

#[derive(Clone)]
pub struct Endpoint {
    pub port: u16,
    pub secret: String,
}

struct Engine {
    endpoint: Endpoint,
    stats: Arc<Stats>,
    task: JoinHandle<Result<(), String>>,
    runtime: Runtime,
}

static ENGINE: OnceLock<Mutex<Option<Engine>>> = OnceLock::new();

fn engine() -> &'static Mutex<Option<Engine>> {
    ENGINE.get_or_init(|| Mutex::new(None))
}

/// Returns only after the listener and shutdown channel are ready. No remote
/// connection or account operation is needed to start the engine.
pub fn start() -> Result<Endpoint, String> {
    let mut guard = engine().lock().map_err(|_| "TGLock state unavailable")?;
    if let Some(current) = guard.as_ref() {
        if current.stats.running.load(Ordering::SeqCst) && !current.task.is_finished() {
            return Ok(current.endpoint.clone());
        }
    }
    if let Some(old) = guard.take() {
        shutdown(old);
    }
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .thread_name("tglock-net")
        .build()
        .map_err(|e| e.to_string())?;
    let stats = Stats::with_secret(rand::random());
    // Never expose a LAN listener or an arbitrary-destination SOCKS relay.
    let config = ListenConfig::loopback(0).with_allow_direct(false);
    let (port, task) = runtime.block_on(async {
        let listener = proxy::bind(config).await?;
        let port = listener.local_addr().map_err(|e| e.to_string())?.port();
        let mut server = Box::pin(proxy::serve(stats.clone(), listener, false));
        // Poll once on this thread: upstream installs its shutdown channel
        // before waiting on the listener. A rapid toggle can now stop safely.
        std::future::poll_fn(|cx| match server.as_mut().poll(cx) {
            Poll::Pending => Poll::Ready(Ok(())),
            Poll::Ready(result) => Poll::Ready(Err(result
                .err()
                .unwrap_or_else(|| "Listener stopped".into()))),
        })
        .await?;
        let task = tokio::spawn(server);
        Ok::<_, String>((port, task))
    })?;
    if !stats.running.load(Ordering::SeqCst) || task.is_finished() {
        task.abort();
        runtime.shutdown_timeout(Duration::from_secs(1));
        return Err("TGLock listener failed to start".into());
    }
    let endpoint = Endpoint {
        port,
        secret: stats.telegram_secret(),
    };
    *guard = Some(Engine {
        endpoint: endpoint.clone(),
        stats,
        task,
        runtime,
    });
    Ok(endpoint)
}

fn shutdown(current: Engine) {
    current.stats.stop();
    current.runtime.block_on(async {
        if tokio::time::timeout(Duration::from_secs(1), current.task)
            .await
            .is_err()
        {
            // Dropping the runtime below also cancels any remaining tunnel.
        }
    });
    current.runtime.shutdown_timeout(Duration::from_secs(1));
}

pub fn stop() -> Result<(), String> {
    let current = engine()
        .lock()
        .map_err(|_| "TGLock state unavailable")?
        .take();
    if let Some(current) = current {
        shutdown(current);
    }
    Ok(())
}

pub fn status() -> Result<String, String> {
    let guard = engine().lock().map_err(|_| "TGLock state unavailable")?;
    Ok(match guard.as_ref() {
        Some(current) => serde_json::json!({
            "running": current.stats.running.load(Ordering::SeqCst) && !current.task.is_finished(),
            "port": current.endpoint.port,
            "connections": current.stats.active.load(Ordering::Relaxed),
            "tunnels": current.stats.ws.load(Ordering::Relaxed),
            "failures": current.stats.route_failures(),
            "failed_connections": current.stats.ws_failures.load(Ordering::Relaxed),
            "blocked": current.stats.blocked.load(Ordering::Relaxed),
            "unknown_clients": current.stats.unknown_clients.load(Ordering::Relaxed),
            "silent_clients": current.stats.silent_clients.load(Ordering::Relaxed),
            "events": current.stats.drain_events(),
            "dc": current.stats.last_dc(),
            "route": current.stats.last_route(),
        })
        .to_string(),
        None => "{\"running\":false}".into(),
    })
}

fn java_string(mut env: JNIEnv, action: impl FnOnce() -> Result<String, String>) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(action));
    match result {
        Ok(Ok(value)) => match env.new_string(value) {
            Ok(value) => value.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        _ => {
            // Do not put endpoint secrets or network payloads into exceptions.
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "TGLock engine unavailable",
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_org_telegram_messenger_TgLockController_nativeStart(
    env: JNIEnv,
    _: JClass,
) -> jstring {
    java_string(env, || {
        let endpoint = start()?;
        Ok(serde_json::json!({ "port": endpoint.port, "secret": endpoint.secret }).to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_org_telegram_messenger_TgLockController_nativeStop(
    mut env: JNIEnv,
    _: JClass,
) {
    if !matches!(std::panic::catch_unwind(stop), Ok(Ok(()))) {
        let _ = env.throw_new("java/lang/IllegalStateException", "Unable to stop TGLock");
    }
}

#[no_mangle]
pub extern "system" fn Java_org_telegram_messenger_TgLockController_nativeStatus(
    env: JNIEnv,
    _: JClass,
) -> jstring {
    java_string(env, status)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::{Ipv4Addr, SocketAddrV4, TcpListener, TcpStream};

    #[test]
    fn lifecycle_and_local_policy() {
        let first = start().unwrap();
        let second = start().unwrap();
        assert_eq!(first.port, second.port);
        assert_eq!(first.secret, second.secret);
        assert_eq!(first.secret.len(), 34); // dd + 16 random bytes
        let mut client =
            TcpStream::connect(SocketAddrV4::new(Ipv4Addr::LOCALHOST, first.port)).unwrap();
        client
            .set_read_timeout(Some(Duration::from_secs(3)))
            .unwrap();
        client.write_all(&[5, 1, 0]).unwrap();
        let mut reply = [0; 2];
        client.read_exact(&mut reply).unwrap();
        assert_eq!(reply, [5, 0]);
        // An unrelated destination must be rejected before any outgoing dial.
        client
            .write_all(&[5, 1, 0, 1, 192, 0, 2, 1, 0, 80])
            .unwrap();
        let mut denied = [0; 10];
        client.read_exact(&mut denied).unwrap();
        assert_ne!(denied[1], 0);
        stop().unwrap();
        assert!(status().unwrap().contains("false"));
        TcpListener::bind(SocketAddrV4::new(Ipv4Addr::LOCALHOST, first.port)).unwrap();
        let third = start().unwrap();
        assert_ne!(first.secret, third.secret);
        stop().unwrap();
        stop().unwrap();
    }
}
