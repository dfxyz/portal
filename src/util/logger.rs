use crate::statics;
use crate::util::WaitGroupPermit;
use log::{Log, Metadata, Record};
use std::io::SeekFrom;
use std::mem;
use tokio::fs::{File, OpenOptions};
use tokio::io::{stderr, stdout, AsyncSeekExt, AsyncWriteExt, Stderr, Stdout};
use tokio::sync::mpsc;
use tokio::{fs, select, task};

const DATETIME_FORMAT: &str = "%F %T%.3f";
const LOG_FILE_BASE_NAME: &str = "portal.log";

pub(crate) async fn init(_permit: WaitGroupPermit) {
    let working_dir = statics::working_dir();
    let config = &statics::config().logger;

    log::set_max_level(config.level.to_level_filter());

    let stdio_handles = if config.use_stdio {
        let stdout = stdout();
        let stderr = stderr();
        Some(LoggerStdioHandles { stdout, stderr })
    } else {
        None
    };

    let file_status = if config.use_file {
        let mut file = OpenOptions::new()
            .create(true)
            .write(true)
            .open(working_dir.join(LOG_FILE_BASE_NAME))
            .await
            .unwrap();
        file.seek(SeekFrom::End(0)).await.unwrap();
        let file_len = file.stream_position().await.unwrap() as _;
        Some(LoggerFileStatus { file, file_len })
    } else {
        None
    };

    let (tx, rx) = mpsc::unbounded_channel();
    let backend = LoggerBackend {
        rx,
        stdio_handles,
        file_status,
        _permit,
    };
    task::spawn(LoggerBackend::run(backend));
    log::set_boxed_logger(Box::new(LoggerFrontend { tx })).unwrap();
}

enum LoggerCommand {
    Log { content: String, is_error: bool },
    Flush,
}

struct LoggerFrontend {
    tx: mpsc::UnboundedSender<LoggerCommand>,
}

struct LoggerBackend {
    rx: mpsc::UnboundedReceiver<LoggerCommand>,
    stdio_handles: Option<LoggerStdioHandles>,
    file_status: Option<LoggerFileStatus>,
    _permit: WaitGroupPermit,
}
struct LoggerStdioHandles {
    stdout: Stdout,
    stderr: Stderr,
}
struct LoggerFileStatus {
    file: File,
    file_len: usize,
}

impl Log for LoggerFrontend {
    #[inline]
    fn enabled(&self, _: &Metadata) -> bool {
        true // controlled by log::max_level()
    }

    fn log(&self, record: &Record) {
        let datetime = chrono::Local::now().format(DATETIME_FORMAT);
        let level = record.level();
        let file = record.file().unwrap_or("null");
        let line = record.line().unwrap_or(0);
        let target = record.target();
        let args = record.args();
        let content = if target.is_empty() {
            format!("[{datetime}][{level}][{file}:{line}] {args}")
        } else {
            format!("[{datetime}][{level}][{file}:{line}] {target}: {args}")
        };
        let is_error = level <= log::Level::Error;
        let _ = self.tx.send(LoggerCommand::Log { is_error, content });
    }

    #[inline]
    fn flush(&self) {
        let _ = self.tx.send(LoggerCommand::Flush);
    }
}

impl LoggerBackend {
    async fn run(mut self) {
        loop {
            select! {
                cmd = self.rx.recv() => {
                    match cmd {
                        None => return,
                        Some(cmd) => self.handle_cmd(cmd).await,
                    }
                }
                _ = statics::root_context().cancelled() => break,
            }
        }
        self.rx.close();
        while let Some(cmd) = self.rx.recv().await {
            self.handle_cmd(cmd).await;
        }
    }

    async fn handle_cmd(&mut self, cmd: LoggerCommand) {
        match cmd {
            LoggerCommand::Log { content, is_error } => {
                self.log(content, is_error).await;
            }
            LoggerCommand::Flush => {
                self.flush().await;
            }
        }
    }

    async fn log(&mut self, content: String, is_error: bool) {
        if let Some(handles) = self.stdio_handles.as_mut() {
            if is_error {
                handles.stderr.write_all(content.as_bytes()).await.unwrap();
            } else {
                handles.stdout.write_all(content.as_bytes()).await.unwrap();
            }
        }
        if let Some(file_status) = self.file_status.as_mut() {
            file_status.file_len += content.as_bytes().len();
            file_status.file.write_all(content.as_bytes()).await.unwrap();

            let rotate_file_num_limit = statics::config().logger.rotate_file_num_limit;
            let rotate_file_len_threshold = statics::config().logger.rotate_file_len_threshold;
            if rotate_file_num_limit > 0 && file_status.file_len >= rotate_file_len_threshold {
                let working_dir = statics::working_dir();
                for i in (1..rotate_file_num_limit).rev() {
                    let j = i + 1;
                    let from = working_dir.join(format!("{LOG_FILE_BASE_NAME}.{i}"));
                    let to = working_dir.join(format!("{LOG_FILE_BASE_NAME}.{j}"));
                    let _ = fs::rename(from, to).await;
                }
                let log_file_path = working_dir.join(LOG_FILE_BASE_NAME);
                let _ = fs::rename(&log_file_path, working_dir.join(format!("{LOG_FILE_BASE_NAME}.1"))).await;
                let file = OpenOptions::new().create(true).write(true).truncate(true).open(log_file_path).await.unwrap();
                let _ = mem::replace(&mut file_status.file, file);
                file_status.file_len = 0;
            }
        }
    }

    async fn flush(&mut self) {
        if let Some(handles) = self.stdio_handles.as_mut() {
            handles.stdout.flush().await.unwrap();
            handles.stderr.flush().await.unwrap();
        }
        if let Some(file_status) = self.file_status.as_mut() {
            file_status.file.flush().await.unwrap();
        }
    }
}
