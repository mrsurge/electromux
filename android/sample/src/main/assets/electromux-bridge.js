'use strict';
// Transport-independent browser API. Native registration remains authoritative.
((root, factory) => {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.ElectromuxBridge = factory();
})(typeof globalThis === 'object' ? globalThis : this, () => {
  const record = value => value !== null && typeof value === 'object' && !Array.isArray(value);
  function create({query, methods, events = [], timeoutMs = 10000, maxPending = 8}) {
    if (typeof query !== 'function' || !Number.isFinite(timeoutMs) || timeoutMs <= 0 ||
        !Number.isInteger(maxPending) || maxPending < 1 || maxPending > 64) throw new Error('Invalid bridge configuration');
    const allowed = new Set(methods);
    const listeners = new Map(events.map(name => [name, new Set()]));
    const pending = new Map();
    let nextId = 1;
    let closed = false;
    function request(method, params = {}) {
      if (closed || !allowed.has(method) || !record(params) || pending.size >= maxPending)
        return Promise.reject(new Error('Bridge closed, unsupported request, or queue full'));
      let id;
      do { id = nextId; nextId = nextId === 2147483647 ? 1 : nextId + 1; } while (pending.has(id));
      return new Promise((resolve, reject) => {
        const finish = (error, value) => {
          if (!pending.has(id)) return;
          clearTimeout(pending.get(id).timer);
          pending.delete(id);
          if (error) reject(error); else resolve(value);
        };
        const timer = setTimeout(() => finish(new Error('Native request timed out; no retry performed')), timeoutMs);
        pending.set(id, {timer, finish});
        try {
          const raw = JSON.stringify({id, method, params});
          if (new TextEncoder().encode(raw).length > 4096) throw new Error('Native request too large');
          query({request: raw, onSuccess: response => {
            if (!pending.has(id)) return;
            try {
              if (typeof response !== 'string' || new TextEncoder().encode(response).length > 65536)
                throw new Error('Invalid bridge response size');
              const envelope = JSON.parse(response);
              if (!record(envelope) || envelope.id !== id || !record(envelope.result) ||
                  typeof envelope.result.ok !== 'boolean') throw new Error('Invalid bridge response');
              if (!envelope.result.ok) {
                const detail = envelope.result.error;
                if (!record(detail) || typeof detail.message !== 'string') throw new Error('Invalid bridge error');
                const error = new Error(detail.message);
                error.name = 'ElectromuxNativeRequestError';
                if (typeof detail.code === 'string') error.code = detail.code;
                finish(error);
                return;
              }
              if (!Object.hasOwn(envelope.result, 'value')) throw new Error('Missing bridge result');
              const notifications = envelope.events || [];
              if (!Array.isArray(notifications) || notifications.length > 16 || notifications.some(event =>
                !record(event) || !listeners.has(event.name) || !record(event.payload)))
                throw new Error('Invalid bridge events');
              const value = envelope.result.value;
              finish(null, value);
              for (const event of notifications) for (const callback of [...listeners.get(event.name)]) {
                if (closed) break;
                try { callback(event.payload); } catch (_) { /* One consumer cannot strand replies. */ }
              }
            } catch (error) { finish(error); }
          }, onFailure: (code, message) => finish(new Error(
            `Native request rejected (${code}): ${String(message || '').slice(0, 200)}`))});
        } catch (error) { finish(error); }
      });
    }
    function on(name, callback) {
      if (closed || !listeners.has(name) || typeof callback !== 'function') throw new Error('Unsupported event');
      const set = listeners.get(name);
      set.add(callback);
      return () => set.delete(callback);
    }
    function dispose() {
      closed = true;
      for (const entry of [...pending.values()]) entry.finish(new Error('Bridge disposed'));
      for (const set of listeners.values()) set.clear();
    }
    return Object.freeze({request, on, dispose});
  }
  return Object.freeze({create});
});
