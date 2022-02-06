use crate::config::Config;
use crate::util::{Context, WaitGroup};
use std::path::PathBuf;

mod macros;

pub(crate) mod config;
pub(crate) mod service;
pub(crate) mod util;

pub(crate) mod statics {
    use super::*;
    use crate::macros::uninit;

    uninit!(working_dir: PathBuf);
    uninit!(config: Config);
    uninit!(root_context: Context);
}

pub mod proto {
    include!(concat!(env!("OUT_DIR"), "/portal.rs"));
}

pub async fn run(working_dir: PathBuf, config: Config) {
    unsafe {
        statics::set_working_dir(working_dir);
        statics::set_config(config);
        statics::set_root_context(Context::new());
    };

    let wg = WaitGroup::default();

    util::logger::init(wg.new_permit()).await;
    service::control::init(wg.new_permit()).await;

    wg.await;
}
