# Desktop SMB support

The desktop SMB implementation is available on Windows and Linux through `DesktopSmbClient` and `DesktopSmbServer`.

## Connecting to an SMB share

`DesktopSmbClient.connect(host, share, credentials)` opens an SMB2/SMB3 connection. The returned session can list directories and open independent file handles. Each file handle reads by absolute offset, which allows a caller to implement seeking without sharing mutable cursor state.

Credentials can be stored with `saveCredentials` and loaded with `loadCredentials`. They are stored through the desktop secret-store abstraction rather than in application preferences. Anonymous access is selected when the username is blank.

## Sharing a local folder

The desktop build delegates server duties to the operating system's SMB implementation:

- Windows: Windows SMB file sharing through the `New-SmbShare` and `Remove-SmbShare` PowerShell cmdlets.
- Linux: Samba's `net conf` interface, followed by a reload of the `smb` service when systemd is available.

Linux requires Samba to be installed and configured. Creating/removing shares may require elevated privileges. Windows requires the SMB Server feature and permissions to manage shares. The share manager does not silently change firewall rules or create user accounts.

Shares are read-only by default. Choose a share name containing only letters, digits, spaces, dots, underscores, and hyphens. The selected directory must already exist.

## Build dependency

The desktop module uses SMBJ 0.15.0, matching the version already used by the Android application. SMBJ is the SMB client library; server operations are intentionally delegated to native Windows/Samba services.
