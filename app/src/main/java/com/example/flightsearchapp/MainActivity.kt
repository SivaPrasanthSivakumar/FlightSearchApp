package com.example.flightsearchapp

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.room.*
import com.example.flightsearchapp.ui.theme.FlightSearchAppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.FileOutputStream

@Entity(tableName = "airport")
data class Airport(
    @PrimaryKey val id: Int,
    @ColumnInfo(name = "iata_code") val iataCode: String,
    val name: String,
    val passengers: Int
) {
    val code: String
        get() = iataCode
}

@Entity(tableName = "favorite")
data class Favorite(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "departure_code") val departureCode: String,
    @ColumnInfo(name = "destination_code") val destinationCode: String,
)

@Dao
interface FlightDao {
    @Query("SELECT * FROM airport WHERE iata_code LIKE :query OR name LIKE :query ORDER BY passengers DESC")
    suspend fun searchAirports(query: String): List<Airport>

    @Query("SELECT * FROM airport WHERE iata_code = :code")
    fun getAirportByCode(code: String): Flow<Airport?>

    @Query("SELECT * FROM favorite")
    fun getFavorites(): Flow<List<Favorite>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFavorite(favorite: Favorite)

    @Query("DELETE FROM favorite WHERE departure_code = :departureCode AND destination_code = :destinationCode")
    suspend fun deleteFavorite(departureCode: String, destinationCode: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE departure_code = :departureCode AND destination_code = :destinationCode)")
    fun isFavorite(departureCode: String, destinationCode: String): Flow<Boolean>

    @Query("SELECT * FROM airport WHERE iata_code != :departureCode")
    fun getAllOtherAirports(departureCode: String): Flow<List<Airport>>
}

@Database(entities = [Airport::class, Favorite::class], version = 1, exportSchema = false)
abstract class FlightDatabase : RoomDatabase() {
    abstract fun flightDao(): FlightDao

    companion object {
        @Volatile
        private var INSTANCE: FlightDatabase? = null

        fun getInstance(context: Context): FlightDatabase {
            return INSTANCE ?: synchronized(this) {
                val dbName = "flight_search.db"
                val dbPath = context.getDatabasePath(dbName)

                if (!dbPath.exists()) {
                    dbPath.parentFile?.mkdirs()
                    context.assets.open(dbName).use { input ->
                        FileOutputStream(dbPath).use { output -> input.copyTo(output) }
                    }
                }

                Room.databaseBuilder(context, FlightDatabase::class.java, dbName)
                    .createFromFile(dbPath)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var flightDao: FlightDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        flightDao = FlightDatabase.getInstance(applicationContext).flightDao()

        enableEdgeToEdge()
        setContent {
            FlightSearchAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Scaffold { innerPadding ->
                        FlightSearchScreen(flightDao, Modifier.padding(innerPadding))
                    }
                }
            }
        }
    }
}

@Composable
fun FlightSearchScreen(flightDao: FlightDao, modifier: Modifier = Modifier) {
    var departureSearchQuery by remember { mutableStateOf("") }
    var matchedDepartureAirports by remember { mutableStateOf(emptyList<Airport>()) }
    var selectedDepartureAirport by remember { mutableStateOf<Airport?>(null) }
    var savedFavoriteFlights by remember { mutableStateOf(emptyList<Favorite>()) }
    var availableDestinationAirports by remember { mutableStateOf(emptyList<Airport>()) }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        flightDao.getFavorites().collect { favorites ->
            savedFavoriteFlights = favorites
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Blue)
        ) {
            Text(
                "Flight Search",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Image(
            painter = painterResource(R.drawable.flight_icon),
            contentDescription = "Flight icon",
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally)
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = departureSearchQuery,
            onValueChange = { query ->
                departureSearchQuery = query
                selectedDepartureAirport = null
                coroutineScope.launch {
                    matchedDepartureAirports =
                        if (query.isBlank()) emptyList() else flightDao.searchAirports("%$query%")
                }
            },
            label = { Text("Enter departure airport") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        when {
            selectedDepartureAirport != null -> {
                LaunchedEffect(selectedDepartureAirport) {
                    flightDao.getAllOtherAirports(selectedDepartureAirport!!.code).collect {
                        availableDestinationAirports = it
                    }
                }

                Text(
                    "Flights from ${selectedDepartureAirport!!.name} (${selectedDepartureAirport!!.code})",
                    style = MaterialTheme.typography.titleMedium
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(availableDestinationAirports) { destinationAirport ->
                        FlightItem(
                            departureAirport = selectedDepartureAirport!!,
                            destinationAirport = destinationAirport,
                            flightDao = flightDao,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            departureSearchQuery.isBlank() && savedFavoriteFlights.isNotEmpty() -> {
                Text("Favorite Flights", style = MaterialTheme.typography.titleMedium)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(savedFavoriteFlights) { favoriteFlight ->
                        FavoriteFlightItem(
                            favorite = favoriteFlight,
                            flightDao = flightDao,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            departureSearchQuery.isNotBlank() -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(matchedDepartureAirports) { suggestedAirport ->
                        AirportSuggestion(
                            airport = suggestedAirport,
                            onAirportSelected = { chosenAirport ->
                                selectedDepartureAirport = chosenAirport
                                departureSearchQuery =
                                    "${chosenAirport.name} (${chosenAirport.code})"
                                matchedDepartureAirports = emptyList()
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            else -> {
                Text(
                    "Search for flights by entering the departure airport in the search box.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun AirportSuggestion(
    airport: Airport,
    onAirportSelected: (Airport) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .padding(vertical = 4.dp)
            .clickable { onAirportSelected(airport) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${airport.name} (${airport.code})",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun FlightItem(
    departureAirport: Airport,
    destinationAirport: Airport,
    flightDao: FlightDao,
    modifier: Modifier = Modifier
) {
    var isFavorite by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(departureAirport.code, destinationAirport.code) {
        flightDao.isFavorite(departureAirport.code, destinationAirport.code).collect {
            isFavorite = it
        }
    }

    Card(
        modifier = modifier.padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Depart:", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${departureAirport.name} (${departureAirport.code})",
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Arrive:", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${destinationAirport.name} (${destinationAirport.code})",
                    fontWeight = FontWeight.Bold
                )
            }
            IconButton(onClick = {
                coroutineScope.launch {
                    if (isFavorite) {
                        flightDao.deleteFavorite(departureAirport.code, destinationAirport.code)
                    } else {
                        flightDao.insertFavorite(
                            Favorite(
                                departureCode = departureAirport.code,
                                destinationCode = destinationAirport.code
                            )
                        )
                    }
                }
            }) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (isFavorite) "Remove from Favorites" else "Add to Favorites",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun FavoriteFlightItem(favorite: Favorite, flightDao: FlightDao, modifier: Modifier = Modifier) {
    var departureAirport by remember { mutableStateOf<Airport?>(null) }
    var destinationAirport by remember { mutableStateOf<Airport?>(null) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(favorite) {
        coroutineScope.launch {
            flightDao.getAirportByCode(favorite.departureCode).collect { departureAirport = it }
        }
        coroutineScope.launch {
            flightDao.getAirportByCode(favorite.destinationCode).collect { destinationAirport = it }
        }
    }

    if (departureAirport != null && destinationAirport != null) {
        Card(
            modifier = modifier.padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Depart:", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${departureAirport!!.name} (${departureAirport!!.code})",
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Arrive:", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${destinationAirport!!.name} (${destinationAirport!!.code})",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    } else {
        Text("Loading favorite flight...")
    }
}
