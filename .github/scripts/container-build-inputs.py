import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


BUILD_INPUTS_LABEL = "de.ddb.labs.dzp-fcs.build-inputs"
TAGGED_IMAGE = re.compile(r"[a-z0-9][a-z0-9./_-]*:[a-zA-Z0-9_.-]+")
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")


def inspect(image, *options, allow_missing=False):
    result = subprocess.run(
        ["docker", "buildx", "imagetools", "inspect", *options, image],
        capture_output=True,
    )
    if result.returncode:
        error = result.stderr.decode(errors="replace").strip()
        if allow_missing and (
            "manifest unknown" in error.lower() or f"{image.lower()}: not found" in error.lower()
        ):
            return None
        raise SystemExit(error or f"Could not inspect {image}.")
    return result.stdout


def base_images(dockerfile):
    images = []
    stages = set()
    for line in dockerfile.read_text(encoding="utf-8").splitlines():
        fields = line.split()
        if not fields or fields[0].upper() != "FROM":
            continue
        if len(fields) > 1 and fields[1] == "--platform=$TARGETPLATFORM":
            del fields[1]
        if len(fields) < 2:
            raise SystemExit(f"Missing base image in {dockerfile}: {line}")
        image = fields[1]
        if image.lower() not in stages and image.lower() != "scratch":
            if not TAGGED_IMAGE.fullmatch(image):
                raise SystemExit(f"Expected a literal base image tag in {dockerfile}: {line}")
            images.append(image)
        if len(fields) == 4 and fields[2].upper() == "AS":
            stages.add(fields[3].lower())
    if not images:
        raise SystemExit(f"No external base image tags found in {dockerfile}.")
    return images


def matches_platform(config, platform):
    return (config.get("os"), config.get("architecture")) == platform


def manifest_digest(raw_manifest):
    # Buildx --raw preserves the exact registry response bytes.
    return "sha256:" + hashlib.sha256(raw_manifest).hexdigest()


def image_config(image, digest):
    return json.loads(inspect(f"{image}@{digest}", "--format", "{{json .Image}}")) or {}


def platform_digest(image, raw_manifest, platform):
    manifest = json.loads(raw_manifest)
    if "manifests" in manifest:
        for entry in manifest["manifests"]:
            if matches_platform(entry.get("platform") or {}, platform):
                digest = entry.get("digest", "")
                if not DIGEST.fullmatch(digest):
                    raise SystemExit(f"Invalid platform digest for {image}.")
                return digest
    else:
        digest = manifest_digest(raw_manifest)
        if matches_platform(image_config(image, digest), platform):
            return digest
    return None


def main():
    target = os.environ.get("PLATFORM", "linux/amd64")
    platform = tuple(target.split("/"))
    if len(platform) != 2 or not all(platform):
        raise SystemExit(f"Expected a target platform of the form os/architecture: {target}")

    names = base_images(Path(os.environ.get("DOCKERFILE", "release-source/Dockerfile")))
    bases = {}
    for name in dict.fromkeys(names):
        digest = platform_digest(name, inspect(name, "--raw"), platform)
        if digest is None:
            raise SystemExit(f"No {target} manifest found for {name}.")
        bases[name] = digest

    inputs = json.dumps([os.environ["RELEASE_REVISION"], target, list(bases.items())]).encode()
    fingerprint = hashlib.sha256(inputs).hexdigest()
    image = os.environ["IMAGE"]
    release_image = f"{image}:{os.environ['RELEASE_TAG']}"
    published = inspect(release_image, "--raw", allow_missing=True)

    with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
        if published is not None:
            digest = platform_digest(image, published, platform)
            if digest is not None:
                config = image_config(image, digest).get("config") or {}
                labels = config.get("Labels") or {}
                if labels.get(BUILD_INPUTS_LABEL) == fingerprint:
                    print(f"reusable_digest={manifest_digest(published)}", file=output)
                    print(f"Reusing {release_image} with unchanged build inputs.")
                    return

        runtime = names[-1]
        print(f"base_name={runtime}", file=output)
        print(f"base_digest={bases[runtime]}", file=output)
        print(f"fingerprint={fingerprint}", file=output)
        print("contexts<<BASE_IMAGES", file=output)
        for name, digest in bases.items():
            print(f"{name.removesuffix(':latest')}=docker-image://{name}@{digest}", file=output)
        print("BASE_IMAGES", file=output)


if __name__ == "__main__":
    main()
