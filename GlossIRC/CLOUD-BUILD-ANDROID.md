# Build GlossIRC from an Android phone using GitHub Actions

This is the v1.4 alpha source with an added GitHub Actions workflow. It is NOT a verified compiling APK.

## Easiest reliable phone-only upload (Termux + GitHub)

1. Create a **private** empty repository named `GlossIRC` on https://github.com/new (do not initialize with README). Install Termux from F-Droid.
2. Download the `GlossIRC-GitHub-Actions.zip` bundle to your phone's Downloads folder.
3. In Termux run:

    pkg update && pkg install -y git unzip
    termux-setup-storage
    mkdir -p ~/glossirc-upload && cd ~/glossirc-upload
    unzip ~/storage/downloads/GlossIRC-GitHub-Actions.zip
    cd GlossIRC
    git init -b main
    git add .
    git commit -m 'Initial GlossIRC cloud build'
    git remote add origin https://github.com/YOUR_USERNAME/GlossIRC.git
    git push -u origin main

GitHub requires authentication when pushing over HTTPS. Use a fine-grained personal access token with repository Contents write permission as the password, or use GitHub CLI/device login. Never post your token in chat.

4. In your browser, open your repository > **Actions** > **Build GlossIRC APK** > **Run workflow** > Run workflow.
5. After the job finishes, open the run > **Artifacts** > `GlossIRC-debug-apk` and download the artifact ZIP. Extract it on your phone to obtain `app-debug.apk`.
6. Android may require you to enable **Install unknown apps** for your file manager/browser. Only install APKs you built from source you trust.

If compilation fails, open the run's `Build debug APK` step and copy the first actual error message (not only `BUILD FAILED`).

## Notes
- `main` is the expected branch; change the workflow if using another.
- The workflow uses Gradle 8.9 directly because this source archive lacks a Gradle wrapper.
- No DCC, IRC, or script functionality has been tested by this workflow; it checks compilation only.
- The workflow creates a debug APK, not a signed Play Store release.
