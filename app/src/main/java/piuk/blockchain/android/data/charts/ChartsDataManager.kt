package piuk.blockchain.android.data.charts

import info.blockchain.wallet.prices.PriceApi
import info.blockchain.wallet.prices.Scale
import io.reactivex.Observable
import piuk.blockchain.android.data.charts.models.ChartDatumDto
import piuk.blockchain.android.data.currency.CryptoCurrencies
import piuk.blockchain.android.data.rxjava.RxBus
import piuk.blockchain.android.data.rxjava.RxPinning
import piuk.blockchain.android.data.rxjava.RxUtil
import piuk.blockchain.android.util.annotations.Mockable
import java.util.*

@Mockable
class ChartsDataManager(private val historicPriceApi: PriceApi, rxBus: RxBus) {

    private val rxPinning = RxPinning(rxBus)

    //region Convenience methods
    /**
     * Returns a stream of [ChartDatumDto] objects representing prices with timestamps as long a range
     * as possible, with each measurement being 5 days apart. Any data points which have no price are
     * filtered out.
     *
     * @param cryptoCurrency The chosen [CryptoCurrencies] object
     * @param fiatCurrency The fiat currency which you want results for, eg "USD"
     * @return A stream of [ChartDatumDto] objects via an [Observable]
     */
    fun getAllTimePrice(cryptoCurrency: CryptoCurrencies, fiatCurrency: String): Observable<ChartDatumDto> =
            rxPinning.call<ChartDatumDto> {
                getHistoricPriceObservable(cryptoCurrency, fiatCurrency, TimeSpan.ALL_TIME)
            }

    /**
     * Returns a stream of [ChartDatumDto] objects representing prices with timestamps over the last
     * year, with each measurement being 24 hours apart. Any data points which have no price are
     * filtered out.
     *
     * @param cryptoCurrency The chosen [CryptoCurrencies] object
     * @param fiatCurrency The fiat currency which you want results for, eg "USD"
     * @return A stream of [ChartDatumDto] objects via an [Observable]
     */
    fun getYearPrice(cryptoCurrency: CryptoCurrencies, fiatCurrency: String): Observable<ChartDatumDto> =
            rxPinning.call<ChartDatumDto> {
                getHistoricPriceObservable(cryptoCurrency, fiatCurrency, TimeSpan.YEAR)
            }

    /**
     * Returns a stream of [ChartDatumDto] objects representing prices with timestamps over the last
     * month, with each measurement being 2 hours apart. Any data points which have no price are
     * filtered out.
     *
     * @param cryptoCurrency The chosen [CryptoCurrencies] object
     * @param fiatCurrency The fiat currency which you want results for, eg "USD"
     * @return A stream of [ChartDatumDto] objects via an [Observable]
     */
    fun getMonthPrice(cryptoCurrency: CryptoCurrencies, fiatCurrency: String): Observable<ChartDatumDto> =
            rxPinning.call<ChartDatumDto> {
                getHistoricPriceObservable(cryptoCurrency, fiatCurrency, TimeSpan.MONTH)
            }

    /**
     * Returns a stream of [ChartDatumDto] objects representing prices with timestamps over the last
     * week, with each measurement being 1 hour apart. Any data points which have no price are
     * filtered out.
     *
     * @param cryptoCurrency The chosen [CryptoCurrencies] object
     * @param fiatCurrency The fiat currency which you want results for, eg "USD"
     * @return A stream of [ChartDatumDto] objects via an [Observable]
     */
    fun getWeekPrice(cryptoCurrency: CryptoCurrencies, fiatCurrency: String): Observable<ChartDatumDto> =
            rxPinning.call<ChartDatumDto> {
                getHistoricPriceObservable(cryptoCurrency, fiatCurrency, TimeSpan.WEEK)
            }

    /**
     * Returns a stream of [ChartDatumDto] objects representing prices with timestamps over the last
     * day, with each measurement being 15 minutes apart. Any data points which have no price are
     * filtered out.
     *
     * @param cryptoCurrency The chosen [CryptoCurrencies] object
     * @param fiatCurrency The fiat currency which you want results for, eg "USD"
     * @return A stream of [ChartDatumDto] objects via an [Observable]
     */
    fun getDayPrice(cryptoCurrency: CryptoCurrencies, fiatCurrency: String): Observable<ChartDatumDto> =
            rxPinning.call<ChartDatumDto> {
                getHistoricPriceObservable(cryptoCurrency, fiatCurrency, TimeSpan.DAY)
            }
    //endregion

    private fun getHistoricPriceObservable(
            cryptoCurrency: CryptoCurrencies,
            fiatCurrency: String,
            timeSpan: TimeSpan
    ): Observable<ChartDatumDto> {

        val scale = when (timeSpan) {
            TimeSpan.ALL_TIME -> Scale.FIVE_DAYS
            TimeSpan.YEAR -> Scale.ONE_DAY
            TimeSpan.MONTH -> Scale.TWO_HOURS
            TimeSpan.WEEK -> Scale.ONE_HOUR
            TimeSpan.DAY -> Scale.FIFTEEN_MINUTES
        }

        return historicPriceApi.getHistoricPriceSeries(
                cryptoCurrency.symbol,
                fiatCurrency,
                getStartTimeForTimeSpan(timeSpan, cryptoCurrency),
                scale
        ).flatMapIterable { it }
                .filter { it.price != null }
                .map { ChartDatumDto(it) }
                .compose(RxUtil.applySchedulersToObservable())
    }

    private fun getStartTimeForTimeSpan(timeSpan: TimeSpan, cryptoCurrency: CryptoCurrencies): Long {
        val start = when (timeSpan) {
            TimeSpan.ALL_TIME -> return getFirstMeasurement(cryptoCurrency)
            TimeSpan.YEAR -> 365
            TimeSpan.MONTH -> 30
            TimeSpan.WEEK -> 7
            TimeSpan.DAY -> 1
        }

        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -start) }
        return cal.timeInMillis / 1000
    }

    /**
     * Provides the first timestamp for which we have prices, returned in epoch-seconds
     *
     * @param cryptoCurrency The [CryptoCurrencies] that you want a start date for
     * @return A [Long] in epoch-seconds since the start of our data
     */
    private fun getFirstMeasurement(cryptoCurrency: CryptoCurrencies): Long {
        return when (cryptoCurrency) {
            CryptoCurrencies.BTC -> FIRST_BTC_ENTRY_TIME
            CryptoCurrencies.ETHER -> FIRST_ETH_ENTRY_TIME
            CryptoCurrencies.BCH -> FIRST_BCH_ENTRY_TIME
        }
    }

}