// The same routes on Express 5, as a cluster of one worker per CPU -- the usual
// way to run Node in production, since one Node process uses one core.
import cluster from 'node:cluster';
import { availableParallelism } from 'node:os';
import express from 'express';

if (cluster.isPrimary) {
  for (let i = 0; i < availableParallelism(); i++) cluster.fork();
} else {
  const app = express();
  app.set('etag', false);
  app.set('x-powered-by', false);
  app.get('/json', (req, res) => res.json({ message: 'Hello, World!' }));
  app.get('/thread', (req, res) => res.json({ virtual: false }));
  // setTimeout is Node's way to wait without blocking the event loop.
  app.get('/block', (req, res) => {
    setTimeout(() => res.json({ slept: true }), Number(req.query.ms ?? 100));
  });
  app.listen(8080);
}
