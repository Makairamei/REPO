## Ringkasan

Jelaskan perubahan utama pada pull request ini.

## Jenis perubahan

- [ ] Fix provider
- [ ] Provider baru
- [ ] Hapus provider rusak
- [ ] Update kategori/genre
- [ ] Fix build/compile
- [ ] Refactor kecil
- [ ] Dokumentasi/workflow

## Provider/module yang diubah

- 

## Checklist wajib

- [ ] `build.gradle.kts` provider sudah bump version.
- [ ] Status provider sudah sesuai (`1` aktif, `0/3` hanya jika memang perlu).
- [ ] Build/compile sudah dicek atau log error sudah dijelaskan.
- [ ] Search/homepage tidak kosong jika provider aktif.
- [ ] `loadLinks` tidak mengirim URL kosong/relatif ke extractor.
- [ ] Tidak membaca file besar memakai `.text` jika rawan OOM.
- [ ] Perubahan hanya menyentuh provider/module yang relevan.

## Hasil test

Tempel hasil test singkat:

```text
:ProviderName:compileDebugKotlin
BUILD SUCCESSFUL
```

Atau jelaskan jika belum bisa dites.

## Catatan

Tambahkan catatan penting, domain mirror, perubahan server, atau alasan provider dihapus.
