import {runRuntime} from './runtime-host.js';
import {proofFactory} from './proof-consumer.js';

await runRuntime((root, mode) => async (signal, emit) => {
  const consumer = await proofFactory(root, mode)(signal, emit);
  return {...consumer, events: [...(consumer.events || []), 'sample.updated']};
}, async (request, emit) => { await emit('sample.updated', {requestId: request.id, method: request.method}); });
