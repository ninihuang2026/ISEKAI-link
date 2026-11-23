//! `Debug` for the types that carry a secret.

/// `Debug` that prints every field except the secret ones, which show as
/// `<redacted>`.
///
/// **Why not derive it.** A derived `Debug` prints the token along with
/// everything else, and these values are exactly what gets logged with `?value`
/// while a session is being debugged. The portal app's Rust logs reach logcat
/// (#298), where `adb` and anything holding `READ_LOGS` can read them; a desktop
/// client's logs end up in bug reports.
///
/// **Every field has to be named**, shown or redacted: the expansion
/// destructures the struct, so a field added later and listed nowhere is a
/// compile error rather than a field that quietly goes unprinted -- or a secret
/// that quietly goes printed.
///
/// ```ignore
/// redacted_debug!(EndpointToken { token_type, expires_in } redacting { endpoint_token });
/// ```
#[macro_export]
macro_rules! redacted_debug {
    ($ty:ident { $($shown:ident),* $(,)? } redacting { $($secret:ident),+ $(,)? }) => {
        impl ::std::fmt::Debug for $ty {
            fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
                let $ty { $($shown,)* $($secret,)+ } = self;
                $(let _ = $secret;)+
                f.debug_struct(stringify!($ty))
                    $(.field(stringify!($shown), $shown))*
                    $(.field(stringify!($secret), &format_args!("<redacted>")))+
                    .finish()
            }
        }
    };
}

#[cfg(test)]
mod tests {
    struct Example {
        id: String,
        token: String,
        refresh: Option<String>,
    }
    crate::redacted_debug!(Example { id } redacting { token, refresh });

    #[test]
    fn secrets_print_as_redacted_and_the_rest_as_usual() {
        let e = Example {
            id: "ep_1".to_owned(),
            token: "tkt1_SECRET".to_owned(),
            refresh: Some("RT_SECRET".to_owned()),
        };
        let printed = format!("{e:?}");
        assert_eq!(
            printed,
            r#"Example { id: "ep_1", token: <redacted>, refresh: <redacted> }"#
        );
        assert!(!format!("{e:#?}").contains("SECRET"));
    }
}
