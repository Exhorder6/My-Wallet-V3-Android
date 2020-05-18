package piuk.blockchain.android.coincore.impl

import com.blockchain.swap.nabu.datamanagers.BuyOrder
import com.blockchain.swap.nabu.datamanagers.CustodialWalletManager
import com.blockchain.swap.nabu.datamanagers.OrderState
import info.blockchain.balance.CryptoCurrency
import info.blockchain.balance.CryptoValue
import info.blockchain.balance.FiatValue
import io.reactivex.Single
import piuk.blockchain.android.coincore.ActivitySummaryItem
import piuk.blockchain.android.coincore.ActivitySummaryList
import piuk.blockchain.android.coincore.AssetAction
import piuk.blockchain.android.coincore.AvailableActions
import piuk.blockchain.android.coincore.CryptoAccountGroup
import piuk.blockchain.android.coincore.CryptoSingleAccount
import piuk.blockchain.android.coincore.CryptoSingleAccountList
import piuk.blockchain.android.coincore.CustodialActivitySummaryItem
import piuk.blockchain.androidcore.data.exchangerate.ExchangeRateDataManager
import piuk.blockchain.androidcore.data.exchangerate.toFiat
import piuk.blockchain.androidcore.utils.extensions.mapList
import piuk.blockchain.androidcore.utils.extensions.switchToSingleIfEmpty
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

internal const val transactionFetchCount = 50
internal const val transactionFetchOffset = 0

abstract class CryptoSingleAccountBase : CryptoSingleAccount {

    protected abstract val exchangeRates: ExchangeRateDataManager

    protected val cryptoAsset: CryptoCurrency
        get() = cryptoCurrencies.first()

    final override var hasTransactions: Boolean = false
        private set

    final override fun fiatBalance(fiat: String, exchangeRates: ExchangeRateDataManager): Single<FiatValue> =
        balance.map { it.toFiat(exchangeRates, fiat) }

    override fun includes(cryptoAccount: CryptoSingleAccount): Boolean =
        cryptoAccount == this

    fun setHasTransactions(hasTransactions: Boolean) {
        this.hasTransactions = hasTransactions
    }
}

class CustodialTradingAccount(
    cryptoCurrency: CryptoCurrency,
    override val label: String,
    override val exchangeRates: ExchangeRateDataManager,
    val custodialWalletManager: CustodialWalletManager
) : CryptoSingleAccountBase() {

    private val isNonCustodialConfigured = AtomicBoolean(false)

    override val cryptoCurrencies = setOf(cryptoCurrency)

    override val receiveAddress: Single<String>
        get() = Single.error(NotImplementedError("Custodial accounts don't support receive"))

    override val balance: Single<CryptoValue>
        get() = custodialWalletManager.getBalanceForAsset(cryptoAsset)
            .doOnComplete { isNonCustodialConfigured.set(false) }
            .doOnSuccess { isNonCustodialConfigured.set(true) }
            .switchToSingleIfEmpty { Single.just(CryptoValue.zero(cryptoAsset)) }
            .onErrorReturn {
                Timber.d("Unable to get non-custodial balance: $it")
                CryptoValue.zero(cryptoAsset)
            }

    override val activity: Single<ActivitySummaryList>
        get() = custodialWalletManager.getAllBuyOrdersFor(cryptoAsset)
            .mapList { buyOrderToSummary(it) }
            .filterActivityStates()
            .doOnSuccess { setHasTransactions(it.isNotEmpty()) }
            .onErrorReturn { emptyList() }

    override val isFunded: Boolean
        get() = isNonCustodialConfigured.get()

    override val isDefault: Boolean = false // Default is, presently, only ever a non-custodial account.

    override val actions: AvailableActions
        get() = availableActions

    private val availableActions = setOf(
        AssetAction.ViewActivity,
        AssetAction.Send
    )

    private fun buyOrderToSummary(buyOrder: BuyOrder): ActivitySummaryItem =
        CustodialActivitySummaryItem(
            exchangeRates = exchangeRates,
            cryptoCurrency = buyOrder.crypto.currency,
            cryptoValue = buyOrder.crypto,
            fundedFiat = buyOrder.fiat,
            txId = buyOrder.id,
            timeStampMs = buyOrder.created.time,
            status = buyOrder.state,
            fee = buyOrder.fee ?: FiatValue.zero(buyOrder.fiat.currencyCode),
            account = this
        )

    // Stop gap filter, until we finalise which item we wish to display to the user.
    // TODO: This can be done via the API when it's settled
    private fun Single<ActivitySummaryList>.filterActivityStates(): Single<ActivitySummaryList> {
        return flattenAsObservable { list ->
            list.filter {
                it is CustodialActivitySummaryItem && displayedStates.contains(it.status)
            }
        }.toList()
    }

    companion object {
        private val displayedStates = setOf(
            OrderState.FINISHED,
            OrderState.AWAITING_FUNDS,
            OrderState.PENDING_EXECUTION
        )
    }
}

abstract class CryptoSingleAccountNonCustodialBase : CryptoSingleAccountBase() {

//    override val receiveAddress: Single<String>
//        get() = Single.error(NotImplementedError("ReceiveAddress not implemented"))

    override val isFunded: Boolean
        get() = false

    final override val actions: AvailableActions
        get() = availableActions

    private val availableActions = setOf(
        AssetAction.ViewActivity,
        AssetAction.Send,
        AssetAction.Receive,
        AssetAction.Swap
    )
}

// Currently only one custodial account is supported for each asset,
// so all the methods on this can just delegate directly
// to the (required) CryptoSingleAccountCustodialBase

class CryptoAccountCustodialGroup(
    override val label: String,
    override val accounts: CryptoSingleAccountList
) : CryptoAccountGroup {

    private val account: CustodialTradingAccount

    init {
        require(accounts.size == 1)
        require(accounts[0] is CustodialTradingAccount)

        account = accounts[0] as CustodialTradingAccount
    }

    override val cryptoCurrencies: Set<CryptoCurrency>
        get() = account.cryptoCurrencies

    override val balance: Single<CryptoValue>
        get() = account.balance

    override val activity: Single<ActivitySummaryList>
        get() = account.activity

    override val actions: AvailableActions
        get() = account.actions

    override val isFunded: Boolean
        get() = account.isFunded

    override val hasTransactions: Boolean
        get() = account.hasTransactions

    override fun fiatBalance(fiat: String, exchangeRates: ExchangeRateDataManager): Single<FiatValue> =
        balance.map { it.toFiat(exchangeRates, fiat) }

    override fun includes(cryptoAccount: CryptoSingleAccount): Boolean =
        accounts.contains(cryptoAccount)
}

class CryptoAccountCompoundGroup(
    val asset: CryptoCurrency,
    override val label: String,
    override val accounts: CryptoSingleAccountList
) : CryptoAccountGroup {
    override val cryptoCurrencies: Set<CryptoCurrency> = setOf(asset)

    // Produce the sum of all balances of all accounts
    override val balance: Single<CryptoValue>
        get() = Single.zip(
            accounts.map { it.balance }
        ) { t: Array<Any> ->
            t.sumBy { it as CryptoValue }
        }

    private fun <T> Array<T>.sumBy(selector: (T) -> CryptoValue): CryptoValue {
        var sum: CryptoValue = CryptoValue.zero(asset)
        for (element in this) {
            sum += selector(element)
        }
        return sum
    }

    // Al; the activities for all the accounts
    override val activity: Single<ActivitySummaryList>
        get() = Single.zip(
            accounts.map { it.activity }
        ) { t: Array<Any> ->
            t.filterIsInstance<List<ActivitySummaryItem>>().flatten()
        }

    // The intersection of the actions for each account
    override val actions: AvailableActions
        get() = accounts.map { it.actions }.reduce { a, b -> a.intersect(b) }

    // if _any_ of the accounts have transactions
    override val hasTransactions: Boolean
        get() = accounts.map { it.hasTransactions }.any { it }

    // Are any of the accounts funded
    override val isFunded: Boolean =
        accounts.map { it.isFunded }.any { it }

    override fun fiatBalance(fiat: String, exchangeRates: ExchangeRateDataManager): Single<FiatValue> =
        balance.map { it.toFiat(exchangeRates, fiat) }

    override fun includes(cryptoAccount: CryptoSingleAccount): Boolean =
        accounts.contains(cryptoAccount)
}