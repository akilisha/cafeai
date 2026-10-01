// The same middleware, with the same options, in real Express -- the reference
// the spike's headers are compared against.
const express = require('express');
const helmet = require('helmet');
const cors = require('cors');

const app = express();
app.use(helmet());
app.use(cors({ origin: ['https://app.example.com'], credentials: true, maxAge: 600 }));
app.get('/json', (req, res) => res.json({ message: 'Hello, World!' }));
app.listen(Number(process.argv[2] || 8081), () => console.log('reference express on', process.argv[2] || 8081));
