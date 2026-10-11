package dev.mrsurge.electromux.node;
import dev.mrsurge.electromux.node.INodeEvents;
interface IElectronHost {
    void subscribe(INodeEvents observer);
    String request(String method, String parameters);
    void acknowledge(int effectId, String error);
    void close();
}
