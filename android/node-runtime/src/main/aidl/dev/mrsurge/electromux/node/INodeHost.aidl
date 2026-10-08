package dev.mrsurge.electromux.node;
import dev.mrsurge.electromux.node.INodeEvents;
interface INodeHost {
    String request(String method, String parameters);
    void subscribe(INodeEvents observer);
    void unsubscribe(INodeEvents observer);
}
