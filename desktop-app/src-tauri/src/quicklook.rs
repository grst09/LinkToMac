//! Renders a Quick Look thumbnail for documents (PDF, Word, PowerPoint, Excel, …) that the
//! browser can't display on its own, for the Files preview panel — see
//! `dispatch::files::download_result`'s preview branch. There's no cross-platform way to do
//! this, but `qlmanage` ships with every Mac (it's the same generator Finder itself uses), and
//! this whole app is macOS-only already.

use std::path::{Path, PathBuf};

/// Writes `bytes` to a scratch file named `name` and asks `qlmanage` to thumbnail it, returning
/// the resulting PNG bytes. The scratch directory (and everything in it) is removed before this
/// returns, success or failure.
pub fn thumbnail(name: &str, bytes: &[u8]) -> Result<Vec<u8>, String> {
    // Only the file's own name, never a path someone snuck in — `qlmanage` picks its generator
    // from the extension, so we still need a real filename, just not one that can escape the
    // scratch directory.
    let safe_name = Path::new(name)
        .file_name()
        .and_then(|s| s.to_str())
        .filter(|s| !s.is_empty())
        .unwrap_or("preview");

    let scratch_dir = std::env::temp_dir().join(format!("linktomac-ql-{}", uuid::Uuid::new_v4()));
    let _guard = ScratchDirGuard(scratch_dir.clone());
    std::fs::create_dir_all(&scratch_dir).map_err(|e| e.to_string())?;

    let src_path = scratch_dir.join(safe_name);
    std::fs::write(&src_path, bytes).map_err(|e| e.to_string())?;

    let output = std::process::Command::new("qlmanage")
        .args(["-t", "-s", "1000", "-o"])
        .arg(&scratch_dir)
        .arg(&src_path)
        .output()
        .map_err(|e| format!("qlmanage isn't available: {e}"))?;

    if !output.status.success() {
        return Err("Quick Look couldn't generate a preview for this file".to_string());
    }

    let thumb_path = scratch_dir.join(format!("{safe_name}.png"));
    std::fs::read(&thumb_path).map_err(|_| "Quick Look didn't produce a thumbnail".to_string())
}

struct ScratchDirGuard(PathBuf);

impl Drop for ScratchDirGuard {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}
