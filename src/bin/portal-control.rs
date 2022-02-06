use clap::{AppSettings, Parser, Subcommand};
use portal::proto::control_request::Content as RequestContent;
use portal::proto::control_response::Content as ResponseContent;
use portal::proto::{ControlRequest, ControlResponse};
use prost::Message;
use serde::Deserialize;
use std::fs::File;
use std::net::SocketAddr;
use std::time::Duration;
use tokio::net::UdpSocket;
use tokio::select;
use tokio::time::sleep;

const SHORT_TIMEOUT: Duration = Duration::from_secs(1);

#[derive(Parser)]
#[clap(setting(AppSettings::DisableHelpSubcommand))]
struct Args {
    #[clap(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    #[clap(about = "Shutdown the running process (if it exists)")]
    Shutdown,
}

#[derive(Deserialize)]
struct Config {
    pub address: SocketAddr,
}

#[tokio::main(flavor = "current_thread")]
async fn main() {
    let args = Args::parse();
    let config: Config =
        serde_yaml::from_reader(File::open("portal.config.yaml").unwrap()).unwrap();
    let mut addr = config.address;
    addr.set_port(0);
    let socket = UdpSocket::bind(addr).await.unwrap();
    socket.connect(&config.address).await.unwrap();
    match args.command {
        Command::Shutdown => shutdown(socket).await,
    };
}

async fn shutdown(socket: UdpSocket) {
    let request = ControlRequest {
        content: Some(RequestContent::Shutdown(0)),
    };
    let vec = request.encode_to_vec();
    socket.send(&vec).await.unwrap();
    let mut buf = vec![0u8; 1024];
    select! {
        _ = sleep(SHORT_TIMEOUT) => {
            eprintln!("request timeout");
        }
        r = socket.recv(&mut buf) => {
            match r {
                Ok(_) => {
                    println!("commencing shutdown...");
                }
                Err(e) => {
                    eprintln!("failed to receive response: {e}");
                }
            }
        }
    }
}
