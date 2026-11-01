# GlossIRC full consolidated source (v1.7 alpha)

This is a complete project snapshot, **not a patch**. It contains v1.5, v1.6, and v1.7 source together.

## Included
- v1.5: /LIST browser, script editor Remote/Aliases/Popups/Variables, mIRC colors, water bottle icon, nick slide-out
- v1.6: collapsible server tree, saved server profiles (host, port, TLS, preferred nick, ident), IRC name case folding
- v1.7: clickable channel links and confirmation dialog; open channel only after self JOIN confirmation

## Update existing Termux checkout (replace files, not git metadata)
1. Download this ZIP to Downloads.
2. Run:

```sh
cd ~/glossirc-upload/GlossIRC
git status
mkdir -p ~/glossirc-full-update
unzip -o ~/storage/downloads/GlossIRC-v1.7-COMPLETE-all-features.zip -d ~/glossirc-full-update
cp -r ~/glossirc-full-update/GlossIRC/. ./
git add -A
git commit -m "Restore complete v1.5-v1.7 features"
git push origin main
```

If the repository is elsewhere, use the directory where `git status` works.
This is source code; Android build and runtime verification have not been performed here.
