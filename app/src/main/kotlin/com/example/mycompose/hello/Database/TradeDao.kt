package com.example.mycompose.hello.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TradeDao {
    @Insert
    suspend fun insertTrade(trade: TradeEntity)
    
    @Query("SELECT * FROM trades ORDER BY timestamp DESC")
    fun getAllTrades(): Flow<List<TradeEntity>>
    
    @Query("SELECT * FROM trades WHERE symbol = :symbol ORDER BY timestamp DESC")
    fun getTradesBySymbol(symbol: String): Flow<List<TradeEntity>>
    
    @Query("DELETE FROM trades")
    suspend fun deleteAllTrades()
}