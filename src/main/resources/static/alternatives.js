(() => {
  const input=document.getElementById('stop-query'), list=document.getElementById('stop-results');let timer,revision=0;
  input?.addEventListener('input',()=>{clearTimeout(timer);const id=++revision;timer=setTimeout(async()=>{
    if(input.value.trim().length<2){list.replaceChildren();return;}
    try { const res=await fetch('/alternatives/stops?q='+encodeURIComponent(input.value.trim()),{cache:'no-store'});if(!res.ok)throw Error();const rows=await res.json();if(id!==revision)return;list.replaceChildren();
      for(const stop of rows){const row=document.createElement('div');row.className='stop-result';const label=document.createElement('span');label.textContent=stop.name+' ('+stop.id+') ';row.append(label);
        for(const [field,text] of [['origin','Start here'],['destination','End here']]){const b=document.createElement('button');b.type='button';b.className='text-button';b.textContent=text;b.onclick=()=>document.getElementById(field).value=stop.id;row.append(b);}list.append(row);}
      if(!rows.length)list.textContent='No matching stops. Try a shorter name or stop number.';
    } catch(e){if(id===revision)list.textContent='Stop search is unavailable. You can enter a known stop ID.';}
  },300);});
})();
