package com.pasiflonet.mobile.utils

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class DataStoreRepo(private val context: Context) {

    companion object {
        val API_ID_KEY = intPreferencesKey("api_id")
        val API_HASH_KEY = stringPreferencesKey("api_hash")
        val TARGET_USERNAME_KEY = stringPreferencesKey("target_username")
        val LOGO_URI_KEY = stringPreferencesKey("logo_uri")
    }

    val apiId: Flow<Int?> = context.dataStore.data.map { p -> p[API_ID_KEY] }
    val apiHash: Flow<String?> = context.dataStore.data.map { p -> p[API_HASH_KEY] }
    val targetUsername: Flow<String?> = context.dataStore.data.map { p -> p[TARGET_USERNAME_KEY] }
    val logoUri: Flow<String?> = context.dataStore.data.map { p -> p[LOGO_URI_KEY] }

    suspend fun saveApi(id: Int, hash: String) {
        context.dataStore.edit { p ->
            p[API_ID_KEY] = id
            p[API_HASH_KEY] = hash
        }
    }

    suspend fun saveTargetUsername(username: String) {
        context.dataStore.edit { p -> p[TARGET_USERNAME_KEY] = username }
    }

    suspend fun saveLogoUri(uri: String) {
        context.dataStore.edit { p -> p[LOGO_URI_KEY] = uri }
    }
}
