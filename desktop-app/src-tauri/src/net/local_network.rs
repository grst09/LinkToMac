//! Finds the Mac's primary LAN IPv4 address for the pairing QR payload, ported from
//! `mac-app/Sources/LinkToMac/Networking/LocalNetwork.swift` (which used raw POSIX
//! `getifaddrs`, portable as-is — here using the `if-addrs` crate for the same job, since it's
//! already pulled in transitively by `mdns-sd`).

use std::net::Ipv4Addr;

/// Prefers a conventional primary-interface name (`en0` on macOS, `eth0`/`wlan0` on Linux) but
/// falls back to the first non-loopback IPv4 address found if none match — good enough for a
/// single-NIC laptop/desktop, which is the common case this app runs on.
pub fn primary_ipv4_address() -> Option<Ipv4Addr> {
    let interfaces = if_addrs::get_if_addrs().ok()?;

    let preferred_names = ["en0", "eth0", "wlan0"];
    for name in preferred_names {
        if let Some(addr) = interfaces.iter().find_map(|i| {
            if i.name == name && !i.is_loopback() {
                ipv4_of(i)
            } else {
                None
            }
        }) {
            return Some(addr);
        }
    }

    interfaces
        .iter()
        .filter(|i| !i.is_loopback() && !i.is_link_local())
        .find_map(ipv4_of)
}

fn ipv4_of(interface: &if_addrs::Interface) -> Option<Ipv4Addr> {
    match interface.ip() {
        std::net::IpAddr::V4(addr) => Some(addr),
        std::net::IpAddr::V6(_) => None,
    }
}
