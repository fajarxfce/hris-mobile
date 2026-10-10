// Register custom click behavior before Firebase installs its listeners.
self.addEventListener('notificationclick', event => {
  event.stopImmediatePropagation();
  event.notification.close();
  const data = event.notification.data || {};
  const target = data.destination || data.FCM_MSG?.data?.destination || 'inbox';
  const destination = ['overview', 'activity', 'account', 'inbox'].includes(target) ? target : 'inbox';
  const url = new URL(`./#/${destination}`, self.registration.scope).href;
  event.waitUntil(clients.matchAll({type: 'window', includeUncontrolled: true}).then(windows => {
    const existing = windows.find(client => client.url.startsWith(self.registration.scope));
    if (existing) { existing.postMessage({type: 'open-link', url}); return existing.focus(); }
    return clients.openWindow(url);
  }));
});
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()));

importScripts('./firebase-version.js', './firebase-config.js');
if (self.FLUENT_FIREBASE?.firebase) {
  const root = `https://www.gstatic.com/firebasejs/${self.FIREBASE_WEB_SDK_VERSION}`;
  importScripts(`${root}/firebase-app-compat.js`, `${root}/firebase-messaging-compat.js`);
  firebase.initializeApp(self.FLUENT_FIREBASE.firebase);
  firebase.messaging().onBackgroundMessage(payload => {
    // Firebase displays notification payloads. Data-only messages need explicit display.
    if (payload.notification) return;
    const data = payload.data || {};
    if (!data.title) return;
    return self.registration.showNotification(data.title, {
      body: data.body || '', tag: payload.messageId || data.id, data: {destination: data.destination || 'inbox'}
    });
  });
}
