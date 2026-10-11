package dev.mrsurge.electromux.nodeproof;
interface INodeProof {
    String request(String method);
    String lastEvent();
}
