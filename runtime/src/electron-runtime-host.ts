import {Socket} from 'node:net';
import {isAbsolute} from 'node:path';
import {ConsumerHost, type ConsumerFactory} from './consumer-host.js';
import {ElectronChannel} from './electron-channel.js';
import {type NativeEffect} from './electron-compat.js';

/** Persistent consumer lifecycle with the existing, independently readable Electron ACK lane.
 * Native process ownership is unchanged: renderer detachment is not consumer disposal.
 */
export async function runElectronRuntime(
    factory: (root: string, mode: string, apply: (effect: NativeEffect) => Promise<void>) => ConsumerFactory,
    events: readonly string[]): Promise<void> {
  const fd = Number(process.argv[2]);
  const root = process.argv[3];
  const mode = process.argv[4] || 'standalone';
  if (!Number.isInteger(fd) || fd < 3 || !root || !isAbsolute(root))
    throw new Error('Invalid native startup declaration');
  const stream = new Socket({fd, readable: true, writable: true});
  const channel: ElectronChannel = new ElectronChannel(stream, (method, params) => host.dispatch(method, params), 5000, events, true);
  const host: ConsumerHost = new ConsumerHost((signal, emit) => factory(root, mode, effect => channel.apply(effect))(signal, emit),
    (name, data) => channel.publish(name, data));
  const ended = new Promise<void>(resolve => { stream.once('close', resolve); });
  try {
    await host.ready();
    await channel.ready();
    await ended;
  } finally {
    channel.close();
    await host.dispose();
  }
}
