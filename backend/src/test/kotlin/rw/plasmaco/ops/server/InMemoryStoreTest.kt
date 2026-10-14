package rw.plasmaco.ops.server

import rw.plasmaco.ops.server.core.ServerStore

class InMemoryStoreTest : ServerStoreContract() {
    override fun newStore(): ServerStore = InMemoryStore()
}
