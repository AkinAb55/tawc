use std::env;
use std::process::ExitCode;

use tawc_integration::exec_broker::{map_exit, parse_args, print_usage, run_stdio};

fn main() -> ExitCode {
    let args: Vec<String> = env::args().skip(1).collect();
    let invocation = match parse_args(&args) {
        Ok(p) => p,
        Err(e) => {
            eprintln!("tawc-exec: {e}");
            print_usage();
            return ExitCode::from(2);
        }
    };

    match run_stdio(invocation) {
        Ok(code) => map_exit(code),
        Err(e) => {
            eprintln!("tawc-exec: {e}");
            ExitCode::from(255)
        }
    }
}
