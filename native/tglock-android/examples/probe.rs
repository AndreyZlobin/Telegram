//! Account-free transport probe helper. Writes its secret only to the supplied
//! private file and keeps the embedded engine alive until stdin is closed.
use std::{
    fs,
    io::{self, Write},
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
    let mut file = options.open(&path)?;
    file.write_all(endpoint.secret.as_bytes())?;
    drop(file);
    println!("{}", endpoint.port);
    io::stdout().flush()?;
    let mut line = String::new();
    io::stdin().read_line(&mut line)?;
    tglock_android::stop()?;
    fs::remove_file(path)?;
    Ok(())
}
