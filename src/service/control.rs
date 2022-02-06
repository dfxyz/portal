use crate::macros::log_macros;
use crate::proto::control_request::Content as RequestContent;
use crate::proto::control_response::Content as ResponseContent;
use crate::util::WaitGroupPermit;
use crate::{proto, statics};
use prost::Message;
use std::net::SocketAddr;
use tokio::net::UdpSocket;
use tokio::{select, task};

log_macros!("control");

pub(crate) async fn init(permit: WaitGroupPermit) {
    let socket = match UdpSocket::bind(statics::config().address).await {
        Ok(s) => s,
        Err(e) => {
            error!("failed to bind socket: {e}");
            statics::root_context().cancel();
            return;
        }
    };
    task::spawn(run(permit, socket));
}

async fn run(permit: WaitGroupPermit, socket: UdpSocket) {
    loop {
        let mut buf = vec![0u8; 1024];
        select! {
            r = socket.recv_from(&mut buf) => {
                match r {
                    Ok((len, addr)) => {
                        unsafe { buf.set_len(len) };
                        let permit = permit.clone();
                        task::spawn(handle_received_data(permit, buf, addr));
                    }
                    Err(e) => {
                        error!("failed to receive data: {e}");
                        return;
                    }
                }
            }
            _ = statics::root_context().cancelled() => return,
        }
    }
}

async fn handle_received_data(_permit: WaitGroupPermit, data: Vec<u8>, addr: SocketAddr) {
    let request = match proto::ControlRequest::decode(data.as_slice()) {
        Ok(r) => r,
        Err(_) => return,
    };
    let request_content = match request.content {
        Some(c) => c,
        None => return,
    };

    let mut local_addr = statics::config().address;
    local_addr.set_port(0);
    let socket = match UdpSocket::bind(local_addr).await {
        Ok(s) => s,
        Err(e) => {
            error!("failed to bind socket to send back response: {e}");
            return;
        }
    };

    let response_content = match request_content {
        RequestContent::Shutdown(_) => handle_shutdown(),
    };
    let response = proto::ControlResponse {
        content: Some(response_content),
    };
    let vec = response.encode_to_vec();
    if let Err(e) = socket.send_to(&vec, addr).await {
        error!("failed to send back response: {e}");
    }
}

fn handle_shutdown() -> ResponseContent {
    statics::root_context().cancel();
    ResponseContent::Shutdown(0)
}
