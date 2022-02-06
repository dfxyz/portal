use log::debug;
use parking_lot::Mutex;
use std::collections::HashMap;
use std::future::Future;
use std::mem;
use std::ops::{Deref, DerefMut};
use std::pin::Pin;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::task::{Poll, Waker};
use std::time::Duration;
use tokio::{select, task, time};

#[derive(Clone)]
pub(crate) struct Context {
    inner: Arc<ContextInner>,
}

struct ContextInner {
    id: usize,
    parent: Option<Context>,
    status: Mutex<ContextInnerStatus>,
}

enum ContextInnerStatus {
    Alive {
        wakers: Vec<Waker>,
        children: HashMap<usize, Context>,
    },
    Cancelled,
}

impl Default for ContextInnerStatus {
    #[inline]
    fn default() -> Self {
        Self::Alive {
            wakers: Default::default(),
            children: Default::default(),
        }
    }
}

#[derive(PartialEq)]
enum ContextCancelReason {
    User,
    Parent,
    Timeout,
}

pub(crate) struct ContextCancelFuture<'a> {
    ctx: &'a Context,
}

impl Context {
    #[inline]
    fn new0(parent: Option<Context>) -> Self {
        static ID: AtomicUsize = AtomicUsize::new(0);

        let ctx = Self {
            inner: Arc::new(ContextInner {
                id: ID.fetch_add(1, Ordering::SeqCst),
                parent,
                status: Default::default(),
            }),
        };
        match ctx.parent_id() {
            None => debug!("context {} created", ctx.id()),
            Some(parent_id) => debug!("context {} created from parent {}", ctx.id(), parent_id),
        }
        ctx
    }

    #[inline]
    pub(crate) fn new() -> Self {
        Self::new0(None)
    }

    pub(crate) fn with_timeout(duration: Duration) -> Self {
        let ctx = Self::new();
        let ctx_clone = ctx.clone();
        task::spawn(async move {
            select! {
                _ = ctx_clone.cancelled() => {}
                _ = time::sleep(duration) => ctx_clone.cancel0(ContextCancelReason::Timeout),
            }
        });
        ctx
    }

    pub(crate) fn new_child(&self) -> Self {
        let child = Self::new0(Some(self.clone()));
        let mut status = self.inner.status.lock();
        match status.deref_mut() {
            ContextInnerStatus::Alive {
                wakers: _,
                children,
            } => {
                children.insert(child.id(), child.clone());
            }
            ContextInnerStatus::Cancelled => {
                drop(status);
                let mut status = child.inner.status.lock();
                *status = ContextInnerStatus::Cancelled;
            }
        }
        child
    }

    pub(crate) fn new_child_with_timeout(&self, duration: Duration) -> Self {
        let child = self.new_child();
        let status = child.inner.status.lock();
        let spawn_timer = match status.deref() {
            ContextInnerStatus::Alive { .. } => true,
            ContextInnerStatus::Cancelled => false,
        };
        drop(status);
        if spawn_timer {
            let child_clone = child.clone();
            task::spawn(async move {
                select! {
                    _ = child_clone.cancelled() => {}
                    _ = time::sleep(duration) => child_clone.cancel0(ContextCancelReason::Timeout),
                }
            });
        }
        child
    }

    #[inline]
    pub(crate) fn id(&self) -> usize {
        self.inner.id
    }

    #[inline]
    pub(crate) fn parent_id(&self) -> Option<usize> {
        self.inner.parent.as_ref().map(|p| p.id())
    }

    pub(crate) fn cancelled(&self) -> ContextCancelFuture {
        ContextCancelFuture { ctx: self }
    }

    #[inline]
    pub(crate) fn cancel(&self) {
        self.cancel0(ContextCancelReason::User);
    }

    fn cancel0(&self, reason: ContextCancelReason) {
        let mut status = self.inner.status.lock();
        let prev_status = mem::replace(status.deref_mut(), ContextInnerStatus::Cancelled);
        drop(status);

        if let ContextInnerStatus::Alive {
            wakers: waker,
            children,
        } = prev_status
        {
            match reason {
                ContextCancelReason::User => {
                    debug!("context {} cancelled", self.id());
                }
                ContextCancelReason::Parent => {
                    debug!(
                        "context {} cancelled by parent {}",
                        self.id(),
                        self.parent_id().unwrap(),
                    );
                }
                ContextCancelReason::Timeout => {
                    debug!("context {} timeout", self.id());
                }
            }

            for w in waker {
                w.wake();
            }
            for (_, child) in children {
                child.cancel0(ContextCancelReason::Parent);
            }
            if reason != ContextCancelReason::Parent {
                if let Some(p) = &self.inner.parent {
                    let mut status = p.inner.status.lock();
                    if let ContextInnerStatus::Alive {
                        wakers: _,
                        children,
                    } = status.deref_mut()
                    {
                        children.remove(&self.id());
                    }
                }
            }
        }
    }
}

impl<'a> Future for ContextCancelFuture<'a> {
    type Output = ();

    fn poll(self: Pin<&mut Self>, cx: &mut std::task::Context<'_>) -> Poll<Self::Output> {
        let mut status = self.ctx.inner.status.lock();
        match status.deref_mut() {
            ContextInnerStatus::Alive { wakers, .. } => {
                wakers.push(cx.waker().clone());
                Poll::Pending
            }
            ContextInnerStatus::Cancelled => Poll::Ready(()),
        }
    }
}
