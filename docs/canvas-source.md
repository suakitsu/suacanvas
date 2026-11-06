# Canvas corresponding source

Nova Image Studio and Infinite Canvas are AGPL-3.0 at the pinned revisions in
`THIRD_PARTY_NOTICES.md`. Modified deployments must provide users with access to
their corresponding source. This includes the modifications and scripts needed
to build/install the covered version; a bare upstream link is not the source
offer for the modified application.

## Prepare a matching release

1. Commit the reviewed release, including all frontend/bridge/proxy changes.
2. Run `bash scripts/fetch-canvas-lab-upstreams.sh`. It fetches both exact pins
   and refuses to overwrite local modifications in those checkout directories.
3. Build Nova with `canvas-lab/nova-picpilot.Dockerfile`. The backend is now built
   from the pinned source, rather than overlaying an unrelated prebuilt image.
   The vendored Nova frontend replaces the upstream frontend in that build.
4. Build Infinite from `third_party/canvas-lab/infinite-canvas/web` using the
   upstream pinned build recipe and lockfile. Serve its output through the
   tracked Infinite Nginx/bridge configuration. Keep any additional source
   changes tracked and include them in the bundle; the exporter only knows the
   unmodified upstream pin and the tracked OpenImage adaptations.
5. Run `python scripts/export-canvas-source.py`. The resulting ignored
   `canvas-source/` directory contains the exact committed OpenImage snapshot,
   both full pinned upstream source trees, licenses, and revision metadata.
   The exporter verifies upstream archive SHA-256 hashes and excludes Git
   history, local `.env`, uploads and other untracked files. It refuses to
   overwrite an existing archive.
6. Scan and inspect the archive before publication. Mount it at `/source/` in
   both canvas proxies using `docker-compose.canvas-lab.yml`. Open
   `/openimage-source.html` and verify the download and license are reachable
   without GitHub credentials from the same place users access the canvas.

Extract the archive, enter `openimage/`, and use the included build files. Both
upstream directories are already supplied in the paths expected by the Nova
Dockerfile and Infinite build. Preserve upstream notices when redistributing.

The source link in the UI is part of the offer; deploying the UI without its
matching downloadable archive is not a completed source delivery. Include
the source location alongside downloadable binaries and retain access for the
period required by the applicable license. Re-export for every changed release.

## Existing deployments

Check existing deployments and previously built images against their actual
sources. A local archive does not by itself make the download available to
users. Validate image builds, runtime behavior and the source download in the
target environment before deployment.
