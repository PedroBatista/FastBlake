//! A minimal C ABI over the reference Rust BLAKE3 implementation.
//!
//! FastBlake calls into this through the JDK's Foreign Function & Memory API so
//! that the Rust contender is measured *in process*, on the same buffers, in the
//! same JMH iteration as the Java ones. Shelling out to `b3sum` would measure
//! process startup and file I/O instead of the hash.
//!
//! Every entry point is `unsafe` by nature: the caller owns the memory and must
//! keep it alive and correctly sized for the duration of the call. The Java side
//! (`RustEngine`) is the only intended caller and upholds this.

use blake3::Hasher;

/// ABI version. `RustEngine` checks this and refuses to bind a stale library.
pub const ABI_VERSION: u32 = 1;

#[no_mangle]
pub extern "C" fn fb_abi_version() -> u32 {
    ABI_VERSION
}

/// Allocates a hasher in plain hashing mode. Free it with [`fb_hasher_free`].
#[no_mangle]
pub extern "C" fn fb_hasher_new() -> *mut Hasher {
    Box::into_raw(Box::new(Hasher::new()))
}

/// Allocates a hasher in keyed mode. `key` must point to exactly 32 bytes.
///
/// Returns null if `key` is null.
#[no_mangle]
pub extern "C" fn fb_hasher_new_keyed(key: *const u8) -> *mut Hasher {
    if key.is_null() {
        return std::ptr::null_mut();
    }
    let mut k = [0u8; 32];
    // SAFETY: caller guarantees 32 readable bytes at `key`.
    k.copy_from_slice(unsafe { std::slice::from_raw_parts(key, 32) });
    Box::into_raw(Box::new(Hasher::new_keyed(&k)))
}

/// Allocates a hasher in key-derivation mode over a UTF-8 context string.
///
/// Returns null if `context` is null or not valid UTF-8.
#[no_mangle]
pub extern "C" fn fb_hasher_new_derive_key(context: *const u8, context_len: usize) -> *mut Hasher {
    if context.is_null() {
        return std::ptr::null_mut();
    }
    // SAFETY: caller guarantees `context_len` readable bytes at `context`.
    let bytes = unsafe { std::slice::from_raw_parts(context, context_len) };
    match std::str::from_utf8(bytes) {
        Ok(s) => Box::into_raw(Box::new(Hasher::new_derive_key(s))),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Appends `len` bytes at `data` to the hasher. A zero `len` is a no-op.
#[no_mangle]
pub extern "C" fn fb_hasher_update(hasher: *mut Hasher, data: *const u8, len: usize) {
    if hasher.is_null() || len == 0 {
        return;
    }
    // SAFETY: caller guarantees a live hasher and `len` readable bytes at `data`.
    let hasher = unsafe { &mut *hasher };
    hasher.update(unsafe { std::slice::from_raw_parts(data, len) });
}

/// Writes `out_len` bytes of extended output to `out`, leaving the hasher's
/// state untouched so it can be finalized again at a different length.
#[no_mangle]
pub extern "C" fn fb_hasher_finalize(hasher: *const Hasher, out: *mut u8, out_len: usize) {
    if hasher.is_null() || out.is_null() || out_len == 0 {
        return;
    }
    // SAFETY: caller guarantees a live hasher and `out_len` writable bytes at `out`.
    let hasher = unsafe { &*hasher };
    let dest = unsafe { std::slice::from_raw_parts_mut(out, out_len) };
    hasher.finalize_xof().fill(dest);
}

/// Returns the hasher to its initial state, keeping its mode and key.
#[no_mangle]
pub extern "C" fn fb_hasher_reset(hasher: *mut Hasher) {
    if hasher.is_null() {
        return;
    }
    // SAFETY: caller guarantees a live hasher.
    unsafe { &mut *hasher }.reset();
}

/// Releases a hasher. Null is accepted and ignored; double-free is not.
#[no_mangle]
pub extern "C" fn fb_hasher_free(hasher: *mut Hasher) {
    if hasher.is_null() {
        return;
    }
    // SAFETY: `hasher` came from Box::into_raw in one of the constructors above
    // and has not been freed yet.
    drop(unsafe { Box::from_raw(hasher) });
}

/// One-shot hash of `len` bytes at `data` into `out_len` bytes at `out`.
///
/// Equivalent to new/update/finalize/free but without four boundary crossings,
/// which matters when benchmarking small inputs.
#[no_mangle]
pub extern "C" fn fb_hash(data: *const u8, len: usize, out: *mut u8, out_len: usize) {
    if out.is_null() || out_len == 0 {
        return;
    }
    let mut hasher = Hasher::new();
    if !data.is_null() && len > 0 {
        // SAFETY: caller guarantees `len` readable bytes at `data`.
        hasher.update(unsafe { std::slice::from_raw_parts(data, len) });
    }
    // SAFETY: caller guarantees `out_len` writable bytes at `out`.
    hasher
        .finalize_xof()
        .fill(unsafe { std::slice::from_raw_parts_mut(out, out_len) });
}
