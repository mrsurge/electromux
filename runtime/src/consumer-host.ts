export type Consumer = {
  methods: readonly string[];
  events?: readonly string[];
  dispatch(method: string): Promise<Record<string, unknown>>;
  dispose(): Promise<void>;
};
export type Emit = (name: string, data: Record<string, unknown>) => Promise<void>;
export type ConsumerFactory = (signal: AbortSignal, emit: Emit) => Promise<Consumer>;

/** Native-selected factory, retained initialization failure, no mutation queue/replay. */
export class ConsumerHost {
  private readonly controller = new AbortController();
  private readonly consumer: Promise<Consumer>;
  private busy = false;
  private closed = false;
  private disposal: Promise<void> | undefined;
  private initialized: Consumer | undefined;
  constructor(factory: ConsumerFactory, private readonly sink: Emit = async () => {}) {
    this.consumer = Promise.resolve().then(() => factory(this.controller.signal, this.emit)).then(consumer => {
      this.initialized = consumer; return consumer;
    });
  }
  private readonly emit: Emit = async (name, data) => {
    if (this.closed || !this.initialized) throw new Error('Consumer event owner unavailable');
    if (!this.initialized.events?.includes(name)) throw new Error('Undeclared consumer event');
    await this.sink(name, data);
  };
  async ready(): Promise<void> { await this.consumer; if (this.closed) throw new Error('Consumer closed'); }
  async dispatch(method: string): Promise<Record<string, unknown>> {
    if (this.closed || this.busy) throw new Error('Consumer unavailable');
    this.busy = true;
    try {
      const consumer = await this.consumer;
      if (this.closed) throw new Error('Consumer closed');
      if (!consumer.methods.includes(method)) throw new Error('Undeclared consumer method');
      const result = await consumer.dispatch(method);
      if (this.closed) throw new Error('Consumer closed');
      return result;
    } finally { this.busy = false; }
  }
  dispose(): Promise<void> {
    this.closed = true;
    this.controller.abort();
    return this.disposal ||= this.consumer.then(consumer => consumer.dispose(), () => {});
  }
}
