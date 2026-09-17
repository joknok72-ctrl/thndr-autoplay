#!/bin/sh
# copy the website's engine into the server bundle before deploying (Railway/Fly build from server/ only)
cd "$(dirname "$0")" && cp ../public/static/engine.js ../public/static/ai.js static/
