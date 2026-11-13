// FramePlay: service worker "desligado".
// O Stremio Web registra este arquivo em toda abertura. Antes ele guardava o index.html e os scripts
// num cache offline (Workbox) que só era trocado quando ESTE arquivo mudava -> depois de um deploy o
// app abria a versão antiga e só mostrava a nova depois de "Atualizar".
// Agora não há cache offline: este worker não intercepta nenhum pedido (sem 'fetch'), assim o app
// sempre carrega do servidor. Ao ativar, apaga os caches antigos e, se havia cache velho, recarrega
// as abas abertas uma única vez para já mostrarem a versão nova. Não mexe em localStorage/IndexedDB
// (login e dados do Stremio ficam intactos).
self.addEventListener('install', function () { self.skipWaiting(); });
self.addEventListener('message', function (e) {
    if (e.data && e.data.type === 'SKIP_WAITING') self.skipWaiting();
});
self.addEventListener('activate', function (event) {
    event.waitUntil((async function () {
        var apagou = 0;
        try {
            var keys = await caches.keys();
            await Promise.all(keys.map(function (k) { return caches.delete(k); }));
            apagou = keys.length;
        } catch (e) {}
        try { await self.clients.claim(); } catch (e) {}
        if (apagou) {
            // só quando havia cache antigo: evita laço de recarga nas aberturas seguintes
            try {
                var wins = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
                wins.forEach(function (c) { try { c.navigate(c.url); } catch (e) {} });
            } catch (e) {}
        }
    })());
});
