//! Account-free check of the exact embedded engine on a physical Android device.
//! Secret stays in a caller-supplied private file; stdout contains no secret.
use std::{
    fs,
    io::{self, BufRead, Write},
};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let path = std::env::args()
        .nth(1)
        .ok_or("Pass a private secret-file path")?;
    let endpoint = tglock_android::start()?;
    let mut options = fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options.open(&path)?.write_all(endpoint.secret.as_bytes())?;
    println!("{}", endpoint.port);
    io::stdout().flush()?;
    for line in io::stdin().lock().lines() {
        match line?.trim() {
            "status" => println!("{}", tglock_android::status()?),
            "stop" => break,
            _ => continue,
        }
        io::stdout().flush()?;
    }
    tglock_android::stop()?;
    fs::remove_file(path)?;
    Ok(())
}
