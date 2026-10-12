// Capacity contracts commit hundreds of real IndexedDB transactions. Virtual coroutine
// time cannot accelerate browser I/O; retain a finite deadline on shared CI runners.
config.client = config.client || {};
config.client.mocha = { ...config.client.mocha, timeout: 20000 };
