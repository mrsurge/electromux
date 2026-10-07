'use strict';
(() => {
  let busy = false;
  const documentId = [...window.crypto.getRandomValues(new Uint8Array(16))]
    .map(value => value.toString(16).padStart(2, '0')).join('');
  const bridge = window.ElectromuxBridge.create({
    query: options => window.cefriumQuery(options),
    methods: ['connect', 'start', 'ping', 'status', 'detach', 'stop', 'shutdown'],
    events: ['sample.updated', 'sample.state'],
    documentId,
  });
  window.__electromuxReceiveEvent = bridge.receiveEvent;
  window.addEventListener('pagehide', () => {
    bridge.dispose();
    delete window.__electromuxReceiveEvent;
  }, {once: true});
  const status = document.getElementById('status');
  const result = document.getElementById('result');
  bridge.on('sample.updated', payload => {
    document.getElementById('event').textContent = `Event: sample.updated (${payload.id})`;
  });
  bridge.on('sample.state', payload => {
    document.getElementById('event').textContent = `Native event: sample.state (${payload.id}, ${payload.state})`;
  });
  const buttons = [...document.querySelectorAll('[data-method]')];
  for (const button of buttons) button.addEventListener('click', async () => {
    if (busy) return;
    if (typeof window.cefriumQuery !== 'function') {
      status.textContent = 'Native bridge unavailable';
      return;
    }
    busy = true;
    buttons.forEach(item => { item.disabled = true; });
    const method = button.dataset.method;
    const params = method === 'ping' ? {value: 'hello from bundled page'} : {};
    status.textContent = `Pending ${method}`;
    const finish = () => {
      busy = false;
      buttons.forEach(item => { item.disabled = false; });
    };
    try {
      const response = await bridge.request(method, params);
      result.textContent = JSON.stringify(response, null, 2);
      status.textContent = `Completed ${method}`;
    } catch (error) { status.textContent = error.message; }
    finally { finish(); }
  });
})();
