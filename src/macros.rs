pub(crate) use macros::*;

#[macro_export]
macro_rules! impl_display_with_debug {
    ($name:ident) => {
        impl ::std::fmt::Display for $name {
            #[inline]
            fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
                ::std::fmt::Debug::fmt(self, f)
            }
        }
    };
}

#[macro_export]
macro_rules! assert_trait {
    ($name:ident, $tr:ident) => {
        const _: () = {
            fn f<T: $tr>() {}
            let _ = f::<$name>;
        };
    };
}
