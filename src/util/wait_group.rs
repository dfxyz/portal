use parking_lot::Mutex;
use std::future::Future;
use std::pin::Pin;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::task::{Context, Poll, Waker};

#[derive(Default)]
pub(crate) struct WaitGroup {
    inner: Arc<WaitGroupInner>,
}

pub(crate) struct WaitGroupPermit {
    inner: Arc<WaitGroupInner>,
}

#[derive(Default)]
struct WaitGroupInner {
    task_num: AtomicUsize,
    waker: Mutex<Option<Waker>>,
}

impl WaitGroupInner {
    #[inline]
    fn new_permit(self: &Arc<Self>) -> WaitGroupPermit {
        self.task_num.fetch_add(1, Ordering::SeqCst);
        WaitGroupPermit {
            inner: self.clone(),
        }
    }
}

impl WaitGroup {
    #[inline]
    pub(crate) fn new_permit(&self) -> WaitGroupPermit {
        self.inner.new_permit()
    }
}

impl Future for WaitGroup {
    type Output = ();

    fn poll(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Self::Output> {
        if self.inner.task_num.load(Ordering::SeqCst) == 0 {
            Poll::Ready(())
        } else {
            let mut waker = self.inner.waker.lock();
            if self.inner.task_num.load(Ordering::SeqCst) == 0 {
                Poll::Ready(())
            } else {
                waker.replace(cx.waker().clone());
                Poll::Pending
            }
        }
    }
}

impl WaitGroupPermit {
    #[inline]
    pub(crate) fn clone(&self) -> Self {
        self.inner.new_permit()
    }
}

impl Drop for WaitGroupPermit {
    fn drop(&mut self) {
        if self.inner.task_num.fetch_sub(1, Ordering::SeqCst) == 1 {
            let mut waker = self.inner.waker.lock();
            if let Some(w) = waker.take() {
                w.wake()
            }
        }
    }
}
