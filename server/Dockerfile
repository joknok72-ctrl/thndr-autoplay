FROM node:22-slim
WORKDIR /app
COPY server/ ./server/
COPY public/static/engine.js public/static/ai.js ./public/static/
WORKDIR /app/server
ENV PORT=8080
EXPOSE 8080
CMD ["node", "--max-old-space-size=1536", "index.js"]
