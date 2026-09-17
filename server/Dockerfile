FROM node:22-slim
WORKDIR /app/server
COPY . .
ENV PORT=8080
EXPOSE 8080
CMD ["node", "--max-old-space-size=1536", "index.js"]
