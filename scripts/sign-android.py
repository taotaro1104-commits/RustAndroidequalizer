#!/usr/bin/env python3
"""Align, release-sign and verify an Android APK without exposing passwords."""

import argparse
import hashlib
import os
from pathlib import Path
import re
import secrets
import subprocess


ROOT = Path(__file__).resolve().parent.parent


def run(command, env):
    result = subprocess.run(
        [str(value) for value in command], env=env, check=True,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        encoding="utf-8", errors="replace",
    )
    return result.stdout


def main():
    version = re.search(r'versionName\s*=\s*"([^"]+)"',
                        (ROOT / "android/app/build.gradle.kts").read_text()).group(1)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=ROOT / "android/app/build/outputs/apk/release/app-release-unsigned.apk")
    parser.add_argument("--output", type=Path, default=ROOT / f"dist/signed/rust-eq-{version}-release.apk")
    parser.add_argument("--keystore", type=Path, default=ROOT / ".signing/rusteq-release.p12")
    parser.add_argument("--password-file", type=Path, default=ROOT / ".signing/keystore-pass.txt")
    parser.add_argument("--alias", default="rusteq-release")
    parser.add_argument("--build-tools", default="35.0.0")
    parser.add_argument("--create-key", action="store_true", help="Create the first release key if none exists; never replace an existing key")
    args = parser.parse_args()
    source, output = args.input.resolve(), args.output.resolve()
    keystore, password_file = args.keystore.resolve(), args.password_file.resolve()
    if not source.is_file():
        parser.error("Build the unsigned release APK before signing.")
    if source == output or output.exists():
        parser.error("Choose a new output path; existing APKs are not overwritten.")

    java_home = os.environ.get("JAVA_HOME")
    sdk_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not java_home or not sdk_home:
        parser.error("Set JAVA_HOME and ANDROID_HOME (or ANDROID_SDK_ROOT).")
    exe = ".exe" if os.name == "nt" else ""
    java = Path(java_home) / "bin" / ("java" + exe)
    keytool = Path(java_home) / "bin" / ("keytool" + exe)
    build_tools = Path(sdk_home) / "build-tools" / args.build_tools
    zipalign = build_tools / ("zipalign" + exe)
    signer = build_tools / "lib/apksigner.jar"
    for tool in [java, keytool, zipalign, signer]:
        if not tool.is_file():
            parser.error(f"Required tool not found: {tool}")

    env = os.environ.copy()
    if not keystore.exists():
        if not args.create_key:
            parser.error("Release key missing. Restore it, or use --create-key for the first distribution.")
        if not password_file.exists():
            password_file.parent.mkdir(parents=True, exist_ok=True)
            fd = os.open(str(password_file), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                handle.write(secrets.token_urlsafe(32) + "\n")
        env["RUSTEQ_SIGNING_PASSWORD"] = password_file.read_text(encoding="utf-8").strip()
        keystore.parent.mkdir(parents=True, exist_ok=True)
        run([keytool, "-genkeypair", "-keystore", keystore, "-storetype", "PKCS12",
             "-alias", args.alias, "-keyalg", "RSA", "-keysize", "3072",
             "-sigalg", "SHA256withRSA", "-validity", "10000", "-dname", "CN=Rust EQ",
             "-storepass:env", "RUSTEQ_SIGNING_PASSWORD"], env)
        os.chmod(keystore, 0o600)
        print(f"Created release keystore: {keystore}")
    if not password_file.is_file():
        parser.error("Password file missing. Restore it; the existing release key will not be replaced.")
    env["RUSTEQ_SIGNING_PASSWORD"] = password_file.read_text(encoding="utf-8").strip()
    if not env["RUSTEQ_SIGNING_PASSWORD"]:
        parser.error("Password file is empty.")

    output.parent.mkdir(parents=True, exist_ok=True)
    aligned = output.with_name(output.stem + ".aligned.apk")
    pending = output.with_name(output.stem + ".pending.apk")
    if aligned.exists() or pending.exists():
        parser.error("A temporary APK already exists. Inspect it before retrying.")
    try:
        run([zipalign, "-P", "16", "-f", "4", source, aligned], env)
        run([java, "-jar", signer, "sign", "--ks", keystore, "--ks-key-alias", args.alias,
             "--ks-pass", "env:RUSTEQ_SIGNING_PASSWORD",
             "--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
             "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
             "--out", pending, aligned], env)
        # Exercise v2 as well as v3. This changes only the verifier range;
        # AndroidManifest.xml still requires Android 10 / API 29.
        verification = run([java, "-jar", signer, "verify", "--verbose", "--print-certs",
                            "--min-sdk-version", "24", pending], env)
        alignment = run([zipalign, "-c", "-P", "16", "-v", "4", pending], env)
        certificate = run([keytool, "-exportcert", "-rfc", "-keystore", keystore,
                           "-alias", args.alias, "-storepass:env", "RUSTEQ_SIGNING_PASSWORD"], env)
        if "-----BEGIN CERTIFICATE-----" not in certificate:
            raise RuntimeError("Public certificate export failed.")
        pending.replace(output)
        digest = hashlib.sha256(output.read_bytes()).hexdigest()
        output.with_suffix(".apk.sha256").write_text(f"{digest}  {output.name}\n", encoding="utf-8")
        output.with_suffix(".signing.txt").write_text(
            f"File: {output.name}\nSHA-256: {digest}\n\n{verification}\n{alignment}", encoding="utf-8")
        (output.parent / "rusteq-release-cert.pem").write_text(certificate, encoding="utf-8")
        print(verification.strip())
        print(alignment.strip().splitlines()[-1])
        print(f"Signed APK: {output}\nSHA-256: {digest}")
        print("Keep the release keystore and its password file together in a private backup.")
    finally:
        # Remove only the two temporary files in this output directory.
        for temporary in [aligned, pending]:
            if temporary.is_file():
                temporary.unlink()


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        # Tool output can contain certificate metadata, never our password.
        raise SystemExit(error.stdout.strip()) from None
