(() => {
  const status=document.getElementById('notification-status'), enable=document.getElementById('enable-notifications'), disable=document.getElementById('disable-notifications'), stop=document.getElementById('stop-reminders');
  const supported=window.isSecureContext && 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
  async function api(path,data,form=false){const token=document.querySelector('meta[name="_csrf"]')?.content,header=document.querySelector('meta[name="_csrf_header"]')?.content||'X-CSRF-TOKEN';
    const response=await fetch('/notifications'+path,{method:data===undefined?'GET':'POST',cache:'no-store',headers:data===undefined?{}:{[header]:token,'Content-Type':form?'application/x-www-form-urlencoded':'application/json'},body:data===undefined?undefined:form?new URLSearchParams(data):JSON.stringify(data)});
    if(!response.ok)throw Error('Could not update reminders. Refresh your plan and try again.');return response.json();}
  function bytes(value){return Uint8Array.from(atob(value.replace(/-/g,'+').replace(/_/g,'/')),c=>c.charCodeAt(0));}
  async function refresh(){try{const settings=await api('');enable.disabled=!supported||!settings.available;disable.disabled=!settings.subscribed;stop.disabled=!settings.watching;
    status.textContent=!supported?'For iPhone notifications, add this app to your Home Screen and open it there. A supported browser and HTTPS are required.':!settings.available?'Phone reminders are not available on this deployment yet.':settings.watching?'Reminders are active for your selected departure or trip.':settings.subscribed?'Notifications enabled. Choose “Remind me” on a departure, or start a trip.':'Notifications are off. Enable them to receive reminders.';
    return settings;
  }catch(e){status.textContent='Notification settings are unavailable. Your commute planner still works.';return null;}}
  async function subscribe(){if(!supported)throw Error('Open the installed Home Screen app or a browser with Web Push support.');
    const permission=await Notification.requestPermission();if(permission!=='granted')throw Error('Notifications were not enabled. You can change this in your device settings.');
    const settings=await api('');if(!settings.available)throw Error('Phone reminders have not been enabled on this deployment.');
    const reg=await navigator.serviceWorker.register('/sw.js');await navigator.serviceWorker.ready;
    let sub=await reg.pushManager.getSubscription();if(!sub)sub=await reg.pushManager.subscribe({userVisibleOnly:true,applicationServerKey:bytes(settings.publicKey)});
    await api('/subscribe',sub.toJSON());
  }
  enable?.addEventListener('click',async()=>{enable.disabled=true;try{await subscribe();await refresh();}catch(e){status.textContent=e.message;enable.disabled=false;}});
  disable?.addEventListener('click',async()=>{try{await api('/unsubscribe',{});const reg=await navigator.serviceWorker.getRegistration();const sub=await reg?.pushManager.getSubscription();if(sub)await sub.unsubscribe();await refresh();}catch(e){status.textContent=e.message;}});
  stop?.addEventListener('click',async()=>{try{await api('/stop',{});await refresh();}catch(e){status.textContent=e.message;}});
  document.querySelectorAll('[data-watch-token]').forEach(button=>button.addEventListener('click',async()=>{button.disabled=true;try{if(!supported)throw Error('Use a supported browser or the installed iPhone Home Screen app.');if(Notification.permission!=='granted')await subscribe();else{const state=await api('');if(!state.subscribed)await subscribe();}
    await api('/watch',{token:button.dataset.watchToken,option:button.dataset.watchOption},true);await refresh();status.scrollIntoView({behavior:'smooth',block:'center'});
  }catch(e){status.textContent=e.message||'Reminders are unavailable on this device.';}finally{button.disabled=false;}}));
  if(status)refresh();
})();
