package piuk.blockchain.android.coincore.btc

import info.blockchain.balance.CryptoCurrency
import info.blockchain.balance.CryptoValue
import info.blockchain.wallet.payload.PayloadManager
import info.blockchain.wallet.payload.data.Account
import info.blockchain.wallet.payload.data.LegacyAddress
import io.reactivex.Single
import piuk.blockchain.android.coincore.ActivitySummaryItem
import piuk.blockchain.android.coincore.ActivitySummaryList
import piuk.blockchain.android.coincore.impl.CryptoSingleAccountNonCustodialBase
import piuk.blockchain.android.coincore.impl.transactionFetchCount
import piuk.blockchain.android.coincore.impl.transactionFetchOffset
import piuk.blockchain.androidcore.data.exchangerate.ExchangeRateDataManager
import piuk.blockchain.androidcore.data.payload.PayloadDataManager

internal class BtcCryptoAccountNonCustodial(
    override val label: String,
    private val address: String,
    private val payloadManager: PayloadManager,
    private val payloadDataManager: PayloadDataManager,
    override val isDefault: Boolean = false,
    override val exchangeRates: ExchangeRateDataManager
) : CryptoSingleAccountNonCustodialBase() {
    override val cryptoCurrencies = setOf(CryptoCurrency.BTC)

    override val balance: Single<CryptoValue>
        get() = Single.just(payloadManager.getAddressBalance(address))
            .map { CryptoValue.fromMinor(CryptoCurrency.BTC, it) }

    override val receiveAddress: Single<String>
        get() = payloadDataManager.getNextReceiveAddress(
            // TODO: Probably want the index of this address'
            payloadDataManager.getAccount(payloadDataManager.defaultAccountIndex)
        ).singleOrError()

    override val activity: Single<ActivitySummaryList>
        get() = Single.fromCallable {
                    payloadManager.getAccountTransactions(address, transactionFetchCount, transactionFetchOffset)
                    .map {
                        BtcActivitySummaryItem(
                            it,
                            payloadDataManager,
                            exchangeRates,
                            this
                        ) as ActivitySummaryItem
                }
        }
        .doOnSuccess { setHasTransactions(it.isNotEmpty()) }

    constructor(
        jsonAccount: Account,
        payloadManager: PayloadManager,
        payloadDataManager: PayloadDataManager,
        isDefault: Boolean = false,
        exchangeRates: ExchangeRateDataManager
    ) : this(
        jsonAccount.label,
        jsonAccount.xpub,
        payloadManager,
        payloadDataManager,
        isDefault,
        exchangeRates
    )

    constructor(
        legacyAccount: LegacyAddress,
        payloadManager: PayloadManager,
        payloadDataManager: PayloadDataManager,
        exchangeRates: ExchangeRateDataManager
    ) : this(
        legacyAccount.label ?: legacyAccount.address,
        legacyAccount.address,
        payloadManager,
        payloadDataManager,
        false,
        exchangeRates
    )
}