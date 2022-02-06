use proc_macro::TokenStream;
use proc_macro2::Ident;
use quote::quote;
use syn::parse::{Parse, ParseStream};
use syn::{Expr, parse, Token, Type};

#[proc_macro]
pub fn uninit(ts: TokenStream) -> TokenStream {
    let input: Uninit = parse(ts).unwrap();
    let name = input.ident.to_string();
    let ty = input.ty;
    let var = Ident::new(&name.to_uppercase(), input.ident.span());
    let setter = Ident::new(&format!("set_{}", name), input.ident.span());
    let getter = input.ident;
    let output = quote! {
        static mut #var: ::std::mem::MaybeUninit<#ty> = ::std::mem::MaybeUninit::uninit();

        #[inline]
        pub(crate) unsafe fn #setter(v: #ty) {
            #var.write(v);
        }

        #[inline]
        pub(crate) fn #getter() -> &'static #ty {
            unsafe { #var.assume_init_ref() }
        }
    };
    output.into()
}
struct Uninit {
    ident: Ident,
    _colon: Token![:],
    ty: Type,
}
impl Parse for Uninit {
    fn parse(input: ParseStream) -> syn::Result<Self> {
        Ok(Self {
            ident: input.parse()?,
            _colon: input.parse()?,
            ty: input.parse()?,
        })
    }
}

#[proc_macro]
pub fn log_macros(ts: TokenStream) -> TokenStream {
    let input: LogMacros = parse(ts).unwrap();
    let expr = input.expr;
    let output = quote! {
        use log::{debug, info, warn, error};
        #[allow(unused_macros)]
        macro_rules! debug {
            ($($arg:tt)+) => {
                log::debug!(target: #expr, $($arg)+);
            };
        }
        #[allow(unused_macros)]
        macro_rules! info {
            ($($arg:tt)+) => {
                log::info!(target: #expr, $($arg)+);
            };
        }
        #[allow(unused_macros)]
        macro_rules! warn {
            ($($arg:tt)+) => {
                log::warn!(target: #expr, $($arg)+);
            };
        }
        #[allow(unused_macros)]
        macro_rules! error {
            ($($arg:tt)+) => {
                log::error!(target: #expr, $($arg)+);
            };
        }
    };
    output.into()
}
struct LogMacros {
    expr: Expr,
}
impl Parse for LogMacros {
    fn parse(input: ParseStream) -> syn::Result<Self> {
        Ok(Self {
            expr: input.parse()?
        })
    }
}