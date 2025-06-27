import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.solana.actions.Action
import com.solana.api.Api
import com.solana.networking.Network
import com.solana.networking.NetworkingRouterConfig
import com.solana.networking.OkHttpNetworkingRouter
import com.solana.networking.RPCEndpoint
import io.horizontalsystems.solanakit.core.TokenAccountManager
import io.horizontalsystems.solanakit.database.main.MainDatabase
import io.horizontalsystems.solanakit.database.main.MainStorage
import io.horizontalsystems.solanakit.database.transaction.TransactionDatabase
import io.horizontalsystems.solanakit.database.transaction.TransactionStorage
import io.horizontalsystems.solanakit.models.Address
import io.horizontalsystems.solanakit.models.FullTransaction
import io.horizontalsystems.solanakit.models.Transaction
import io.horizontalsystems.solanakit.transactions.SolanaFmService
import io.horizontalsystems.solanakit.transactions.TransactionManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.net.URL

@RunWith(RobolectricTestRunner::class)
class TransactionManagerFlowTest {
    private lateinit var transactionDatabase: TransactionDatabase
    private lateinit var mainDatabase: MainDatabase
    private lateinit var storage: TransactionStorage
    private lateinit var mainStorage: MainStorage
    private lateinit var transactionManager: TransactionManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        transactionDatabase = Room.inMemoryDatabaseBuilder(context, TransactionDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mainDatabase = Room.inMemoryDatabaseBuilder(context, MainDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        storage = TransactionStorage(transactionDatabase, "11111111111111111111111111111111")
        mainStorage = MainStorage(mainDatabase)

        val httpClient = OkHttpClient.Builder().build()
        val config = NetworkingRouterConfig(emptyList(), emptyList())
        val endpoint = RPCEndpoint.custom(URL("https://localhost"), URL("wss://localhost"), Network.devnet)
        val api = Api(OkHttpNetworkingRouter(endpoint, httpClient, config))
        val tokenAccountManager = TokenAccountManager("11111111111111111111111111111111", api, storage, mainStorage, SolanaFmService())
        val action = Action(api, listOf())
        transactionManager = TransactionManager(Address("11111111111111111111111111111111"), storage, action, tokenAccountManager, Network.devnet)
    }

    @After
    fun tearDown() {
        transactionDatabase.close()
        mainDatabase.close()
    }

    @Test
    fun handle_updatesFlowAndStorage() = runBlocking {
        val tx = FullTransaction(
            Transaction(
                hash = "hash1",
                timestamp = 1L,
                fee = BigDecimal.ONE,
                from = "a",
                to = "b",
                amount = BigDecimal.ONE,
                pending = false
            ),
            listOf()
        )

        transactionManager.handle(listOf(tx), listOf())

        assertEquals(1, transactionManager.transactionsFlow.value.size)
        assertEquals("hash1", transactionManager.transactionsFlow.value[0].transaction.hash)

        val stored = transactionManager.getAllTransaction(null, null, null)
        assertEquals(1, stored.size)
        assertEquals("hash1", stored[0].transaction.hash)
    }
}
