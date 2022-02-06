use crate::impl_display_with_debug;
use serde::Deserialize;
use std::net::SocketAddr;

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Config {
    pub address: SocketAddr,
    #[serde(default)]
    pub logger: LoggerConfig,
}
impl_display_with_debug!(Config);

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
#[serde(default)]
pub struct LoggerConfig {
    pub level: log::Level,
    pub use_stdio: bool,
    pub use_file: bool,
    pub rotate_file_num_limit: usize,
    pub rotate_file_len_threshold: usize,
}

impl Default for LoggerConfig {
    #[inline]
    fn default() -> Self {
        #[cfg(debug_assertions)]
        let level = log::Level::Debug;
        #[cfg(not(debug_assertions))]
        let level = log::Level::Info;

        Self {
            level,
            use_stdio: true,
            use_file: true,
            rotate_file_num_limit: 3,
            rotate_file_len_threshold: 128 << 10 << 10,
        }
    }
}
