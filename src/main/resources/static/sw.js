/* Cache only the public offline shell. Never cache authenticated HTML, API replies, CSRF tokens or maps. */
const CACHE = 'jit-offline-shell-v2';
const SHELL = ['/offline.html', '/offline.js', '/app.css'];
self.addEventListener('install', event => event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(SHELL)).then(() => self.skipWaiting())));
self.addEventListener('activate', event => event.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(key => key.startsWith('jit-offline-shell-') && key !== CACHE).map(key => caches.delete(key)))).then(() => self.clients.claim())));
self.addEventListener('fetch', event => {
  const request = event.request, url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== self.location.origin) return;
  if (request.mode === 'navigate') {
    event.respondWith(fetch(request).catch(() => caches.match('/offline.html')));
  } else if (SHELL.includes(url.pathname)) {
    event.respondWith(fetch(request).catch(() => caches.match(url.pathname)));
  }
});



// A push message contains only a short reminder. Private pages and API responses remain uncached.
self.addEventListener('push',event=>{
  let data={title:'Check your commute',body:'Open Just in Transit for current details.',tag:'commute'};
  try{if(event.data)data={...data,...event.data.json()};}catch(e){}
  event.waitUntil(self.registration.showNotification(String(data.title).slice(0,100),{body:String(data.body).slice(0,300),tag:String(data.tag).slice(0,150),icon:'/icon-192.png',data:{url:'/'}}));
});
self.addEventListener('notificationclick',event=>{event.notification.close();event.waitUntil((async()=>{
  const windows=await clients.matchAll({type:'window',includeUncontrolled:true});
  for(const win of windows)if(new URL(win.url).origin===self.location.origin){await win.navigate('/');return win.focus();}
  return clients.openWindow('/');
})());});
