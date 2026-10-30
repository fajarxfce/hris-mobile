// The 1,000-revision capacity contract performs real IndexedDB transactions.
// Keep its assertions intact and allow bounded I/O time on shared CI runners.
config.client = config.client || {};
config.client.mocha = { ...config.client.mocha, timeout: 20000 };
