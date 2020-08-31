package flightapp;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.security.*;
import java.security.spec.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/**
 * Runs queries against a back-end database
 */
public class Query {
    // DB Connection
    private Connection conn;
    private boolean loggedin;
    private String currentUser;
    private List<Itinerary> itineraries;

    // Password hashing parameter constants
    private static final int HASH_STRENGTH = 65536;
    private static final int KEY_LENGTH = 128;

    // Canned queries
    private static final String CHECK_FLIGHT_CAPACITY = "SELECT capacity FROM Flights WHERE fid = ?";
    private PreparedStatement checkFlightCapacityStatement;

    // For check dangling
    private static final String TRANCOUNT_SQL = "SELECT @@TRANCOUNT AS tran_count";
    private PreparedStatement tranCountStatement;

    // create user
    private static final String CREATE_USER_SQL = "INSERT INTO USERS VALUES (?, ?, ?, ?)";
    private PreparedStatement userCreateStatement;
    // check if user exists in the table
    private static final String CHECK_USERS_SQL = "SELECT COUNT(*) as count FROM USERS WHERE username = ?";
    private PreparedStatement existCheckStatement;
    // login
    private static final String LOGIN_USER_SQL = "SELECT * FROM USERS WHERE username = ?";
    private PreparedStatement loginStatement;
    // for direct flight
    private static final String DIRECT_SQL = "SELECT top (?) fid, day_of_month, carrier_id, flight_num, origin_city, " +
            "dest_city, actual_time, capacity, price " +
            "FROM flights where origin_city = ? AND dest_city = ? AND day_of_month = ? AND canceled = 0" +
            "ORDER BY actual_time ASC, fid ASC";
    private PreparedStatement directStatement;
    // for indirect flight
    private static final String INDIRECT_SQL = "SELECT TOP (?) " +
            "f1.fid AS first_fid, " +
            "f1.day_of_month AS first_day_of_month, " +
            "f1.carrier_id AS first_carrier_id, " +
            "f1.flight_num AS first_flight_num, " +
            "f1.origin_city AS first_origin_city, " +
            "f1.dest_city AS first_dest_city, " +
            "f1.actual_time AS first_actual_time, " +
            "f1.capacity AS first_capacity, " +
            "f1.price AS first_price, " +

            "f2.fid AS second_fid, " +
            "f2.day_of_month AS second_day_of_month, " +
            "f2.carrier_id AS second_carrier_id, " +
            "f2.flight_num AS second_flight_num, " +
            "f2.origin_city AS second_origin_city, " +
            "f2.dest_city AS second_dest_city, " +
            "f2.actual_time AS second_actual_time, " +
            "f2.capacity AS second_capacity, " +
            "f2.price AS second_price " +

            "FROM Flights AS f1, Flights AS f2 " +
            "WHERE " +
            "f1.origin_city = ? " +
            "AND f1.dest_city = f2.origin_city " +
            "AND f2.dest_city = ? " +
            "AND f1.day_of_month = ? " +
            "AND f2.day_of_month = ? " +
            "AND f1.canceled = 0 " +
            "AND f2.canceled = 0 " +


            "ORDER BY f1.actual_time + f2.actual_time ASC, f1.fid ASC, f2.fid ASC";
    private PreparedStatement inDirectStatement;

    // check if a user can book a itinerary with the date
    private static final String CHECKDAY_SQL = "SELECT count(*) AS count from RESERVATIONS WHERE username = ? AND day = ? AND cancelled =0";
    private PreparedStatement checkDayStatement;
    // create a reservation ID
    private static final String RESERVID_SQL = "SELECT count(*) AS count from RESERVATIONS";
    private PreparedStatement reservIDStatement;
    // fill out the reservations table with the flight information that a user is booking
    private static final String BOOKING_SQL = "INSERT INTO RESERVATIONS VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    private PreparedStatement resvBookingStatement;
    // get a capacity of flight 1
    private static final String CAPF1_SQL = "SELECT count(*) AS count from RESERVATIONS where fid1 = ? AND cancelled =0";
    private PreparedStatement capF1Statement;
    // get a capacity of flight 2
    private static final String CAPF2_SQL = "SELECT count(*) AS count from RESERVATIONS where fid2 = ? AND cancelled =0";
    private PreparedStatement capF2Statement;
    // get a reservation id and the user name.
    private static final String RESERVUSERID_SQL = "SELECT * from RESERVATIONS where id = ? AND username = ? AND cancelled =0";
    private PreparedStatement reservUserAndIDStatement;
    // look up all the list of reservation for the user
    private static final String RESERV_SQL = "SELECT * from RESERVATIONS where username = ? AND cancelled =0";
    private PreparedStatement reservStatement;
    // to make a format for reservations command
    private static final String RESERVFLIGHT_SQL = "SELECT fid, day_of_month, carrier_id, flight_num, origin_city, dest_city, actual_time, capacity, price " +
            "FROM flights where fid = ?";
    private PreparedStatement reservFlightStatement;
    // get a user information, specifically for balance
    private static final String GETUSER_SQL = "SELECT * from USERS where username = ?";
    private PreparedStatement getUserStatement;
    // when a user paid for the reservation, update it in the table
    private static final String UPDATEPAID_SQL = "UPDATE RESERVATIONS SET paid = 1 WHERE username = ? AND id = ?";
    private PreparedStatement updatePaidStatement;
    // update a user's balance
    private static final String UPDATEBALANCE_SQL = "UPDATE USERS SET balance = ? WHERE username = ?";
    private PreparedStatement updateBalanceStatement;
    // update cancelled when a user cancells reservation
    private static final String CANCELLED_SQL = "UPDATE RESERVATIONS SET cancelled = 1 Where id =? AND username = ? AND cancelled = 0";
    private PreparedStatement cancelledStatement;
    // get the reservation that matches username and id
    private static final String NAMEIDRESER_SQL = "SELECT * from RESERVATIONS where username = ? AND id = ? AND cancelled = 0";
    private PreparedStatement reserCancelStatement;



    public Query() throws SQLException, IOException {
        this(null, null, null, null);
    }

    protected Query(String serverURL, String dbName, String adminName, String password)
            throws SQLException, IOException {
        conn = serverURL == null ? openConnectionFromDbConn()
                : openConnectionFromCredential(serverURL, dbName, adminName, password);

        prepareStatements();
    }

    /**
     * Return a connecion by using dbconn.properties file
     *
     * @throws SQLException
     * @throws IOException
     */
    public static Connection openConnectionFromDbConn() throws SQLException, IOException {
        // Connect to the database with the provided connection configuration
        Properties configProps = new Properties();
        configProps.load(new FileInputStream("dbconn.properties"));
        String serverURL = configProps.getProperty("flightapp.server_url");
        String dbName = configProps.getProperty("flightapp.database_name");
        String adminName = configProps.getProperty("flightapp.username");
        String password = configProps.getProperty("flightapp.password");
        return openConnectionFromCredential(serverURL, dbName, adminName, password);
    }

    /**
     * Return a connecion by using the provided parameter.
     *
     * @param serverURL example: example.database.widows.net
     * @param dbName    database name
     * @param adminName username to login server
     * @param password  password to login server
     * @throws SQLException
     */
    protected static Connection openConnectionFromCredential(String serverURL, String dbName,
                                                             String adminName, String password) throws SQLException {
        String connectionUrl =
                String.format("jdbc:sqlserver://%s:1433;databaseName=%s;user=%s;password=%s", serverURL,
                        dbName, adminName, password);
        Connection conn = DriverManager.getConnection(connectionUrl);

        // By default, automatically commit after each statement
        conn.setAutoCommit(true);

        // By default, set the transaction isolation level to serializable
        conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

        return conn;
    }

    /**
     * Get underlying connection
     */
    public Connection getConnection() {
        return conn;
    }

    /**
     * Closes the application-to-database connection
     */
    public void closeConnection() throws SQLException {
        conn.close();
    }

    /**
     * Clear the data in any custom tables created.
     * <p>
     * WARNING! Do not drop any tables and do not clear the flights table.
     */
    public void clearTables() {
        try {

            Statement clear = conn.createStatement();
            clear.executeUpdate("DELETE FROM RESERVATIONS");
            clear.executeUpdate("DELETE FROM USERS");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /*
     * prepare all the SQL statements in this method.
     */
    private void prepareStatements() throws SQLException {
        checkFlightCapacityStatement = conn.prepareStatement(CHECK_FLIGHT_CAPACITY);
        tranCountStatement = conn.prepareStatement(TRANCOUNT_SQL);
        userCreateStatement = conn.prepareStatement(CREATE_USER_SQL);
        existCheckStatement = conn.prepareStatement(CHECK_USERS_SQL);
        loginStatement = conn.prepareStatement(LOGIN_USER_SQL);
        directStatement = conn.prepareStatement(DIRECT_SQL);
        inDirectStatement = conn.prepareStatement(INDIRECT_SQL);
        checkDayStatement = conn.prepareStatement(CHECKDAY_SQL);
        reservIDStatement = conn.prepareStatement(RESERVID_SQL);
        resvBookingStatement = conn.prepareStatement(BOOKING_SQL);
        capF1Statement = conn.prepareStatement(CAPF1_SQL);
        capF2Statement = conn.prepareStatement(CAPF2_SQL);
        reservUserAndIDStatement = conn.prepareStatement(RESERVUSERID_SQL);
        reservFlightStatement = conn.prepareStatement(RESERVFLIGHT_SQL);
        getUserStatement = conn.prepareStatement(GETUSER_SQL);
        updatePaidStatement = conn.prepareStatement(UPDATEPAID_SQL);
        updateBalanceStatement = conn.prepareStatement(UPDATEBALANCE_SQL);
        reservStatement = conn.prepareStatement(RESERV_SQL);
        cancelledStatement = conn.prepareStatement(CANCELLED_SQL);
        reserCancelStatement = conn.prepareStatement(NAMEIDRESER_SQL);
    }

    /**
     * Takes a user's username and password and attempts to log the user in.
     *
     * @param username user's username
     * @param password user's password
     * @return If someone has already logged in, then return "User already logged in\n" For all other
     * errors, return "Login failed\n". Otherwise, return "Logged in as [username]\n".
     */
    public String transaction_login(String username, String password) {
        try {
            // if loggin is true then the user is already logged in.
            if (loggedin) {
                return "User already logged in\n";
            }
            byte[] hashFromTable = null;
            byte[] saltFromTable = null;

            try {
                // set autocommit to false
                conn.setAutoCommit(false);
                // get a table with username
                loginStatement.clearParameters();
                loginStatement.setString(1, username);
                ResultSet rs = loginStatement.executeQuery();
                // no users registered yet so exit
                if (!rs.next()) {
                    conn.rollback();
                    return "Login failed\n";
                }

                // retrieve the values of the username
                hashFromTable = rs.getBytes("hash");
                saltFromTable = rs.getBytes("salt");

                // get a hashcode from the user's salt password
                SecureRandom random = new SecureRandom();
                byte[] salt = new byte[16];
                random.nextBytes(salt);
                KeySpec spec = new PBEKeySpec(password.toCharArray(), saltFromTable, HASH_STRENGTH, KEY_LENGTH);
                SecretKeyFactory factory = null;
                byte[] hash = null;
                try {
                    factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
                    hash = factory.generateSecret(spec).getEncoded();
                } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
                    throw new IllegalStateException();
                }
                // if the hash from the user table is the same as the hash generated above,
                // then it successfully matches and let them login.
                if (Arrays.equals(hash, hashFromTable)) {
                    // update global variables. these are an indication if a user and its name currently logged in.
                    loggedin = true;
                    currentUser = username;
                    // everything is good so commit.
                    conn.commit();
                    return "Logged in as " + username + "\n";
                } else {
                    conn.rollback();
                    return "Login failed\n";
                }


            } catch (SQLException e) {
                try{
                    // if the error is by deadlock, then recursively call its method
                    // so that the transaction eventually goes through
                    if(isDeadLock(e)){
                        conn.rollback();
                        return transaction_login(username, password);
                    }
                }catch(SQLException ex){
                    ex.printStackTrace();
                }
                e.printStackTrace();
                return "Login failed\n";
            }

        } finally {
            checkDanglingTransaction();
        }
    }

    /**
     * Implement the create user function.
     *
     * @param username   new user's username. User names are unique the system.
     * @param password   new user's password.
     * @param initAmount initial amount to deposit into the user's account, should be >= 0 (failure
     *                   otherwise).
     * @return either "Created user {@code username}\n" or "Failed to create user\n" if failed.
     */
    public String transaction_createCustomer(String username, String password, int initAmount) {
        boolean exists = false;
        try {
            // setup the exists boolean to true if the username already exists in the database
            try {
                conn.setAutoCommit(false);
                existCheckStatement.clearParameters();
                existCheckStatement.setString(1, username);
                // get a table. a cursor is initially pointing before the first row
                ResultSet rs = existCheckStatement.executeQuery();
                // move the cursor to the first row
                rs.next();
                // get the value from the columnlabel
                int count = rs.getInt("count");
                rs.close();
                if (count == 1) {
                    exists = true;
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }

            // it will be done if the username already exists or initAmount < 0
            try {
                if (exists || initAmount < 0) {
                    conn.rollback();
                    return "Failed to create user\n";
                }


                // hashing password
                SecureRandom random = new SecureRandom();
                byte[] salt = new byte[16];
                random.nextBytes(salt);
                KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, HASH_STRENGTH, KEY_LENGTH);
                SecretKeyFactory factory = null;
                byte[] hash = null;
                try {
                    factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
                    hash = factory.generateSecret(spec).getEncoded();
                } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
                    throw new IllegalStateException();
                }
                // complete the statement to insert a user into USERS table
                userCreateStatement.clearParameters();
                userCreateStatement.setString(1, username);
                userCreateStatement.setBytes(2, hash);
                userCreateStatement.setBytes(3, salt);
                userCreateStatement.setInt(4, initAmount);
                userCreateStatement.executeUpdate();
                conn.commit();
                return "Created user " + username + "\n";

            } catch (SQLException e) {
                try{
                    if(isDeadLock(e)){
                        conn.rollback();
                        return transaction_createCustomer(username, password, initAmount);
                    }
                }catch(SQLException ex){
                    ex.printStackTrace();
                }
                e.printStackTrace();
                return "Failed to create user\n";
            }

        }
        finally {
            checkDanglingTransaction();
        }
    }


    /**
     * Implement the search function.
     * <p>
     * Searches for flights from the given origin city to the given destination city, on the given day
     * of the month. If {@code directFlight} is true, it only searches for direct flights, otherwise
     * is searches for direct flights and flights with two "hops." Only searches for up to the number
     * of itineraries given by {@code numberOfItineraries}.
     * <p>
     * The results are sorted based on total flight time.
     *
     * @param originCity
     * @param destinationCity
     * @param directFlight        if true, then only search for direct flights, otherwise include
     *                            indirect flights as well
     * @param dayOfMonth
     * @param numberOfItineraries number of itineraries to return
     * @return If no itineraries were found, return "No flights match your selection\n". If an error
     * occurs, then return "Failed to search\n".
     * <p>
     * Otherwise, the sorted itineraries printed in the following format:
     * <p>
     * Itinerary [itinerary number]: [number of flights] flight(s), [total flight time]
     * minutes\n [first flight in itinerary]\n ... [last flight in itinerary]\n
     * <p>
     * Each flight should be printed using the same format as in the {@code Flight} class.
     * Itinerary numbers in each search should always start from 0 and increase by 1.
     * @see Flight#toString()
     */

    public String transaction_search(String originCity, String destinationCity, boolean directFlight,
                                     int dayOfMonth, int numberOfItineraries) {
        try {
            // WARNING the below code is unsafe and only handles searches for direct flights
            // You can use the below code as a starting reference point or you can get rid
            // of it all and replace it with your own implementation.
            //

            StringBuffer sb = new StringBuffer();
            // it is going to store all the itineraries matches to the user's selection
            itineraries = new ArrayList<>();

            try {
                conn.setAutoCommit(false);

                // completing a String for direct flight statement
                directStatement.clearParameters();
                directStatement.setInt(1, numberOfItineraries);
                directStatement.setString(2, originCity);
                directStatement.setString(3, destinationCity);
                directStatement.setInt(4, dayOfMonth);
                // get a table that we want.
                ResultSet directResults = directStatement.executeQuery();
                // itinerary id which is an index
                int itinaryId = 0;
                // if no matches found, return and exit.
                if (!directResults.next()) {
                    conn.rollback();
                    return "No flights matches your selection\n";
                }
                // used do while loop because direct flight is needed regardless of boolean of directFlight
                do {
                    // retreive all the information from the table we just created to make an instance with
                    // these information
                    int result_fid = directResults.getInt("fid");
                    int result_dayOfMonth = directResults.getInt("day_of_month");
                    String result_carrierId = directResults.getString("carrier_id");
                    String result_flightNum = directResults.getString("flight_num");
                    String result_originCity = directResults.getString("origin_city");
                    String result_destCity = directResults.getString("dest_city");
                    int result_time = directResults.getInt("actual_time");
                    int result_capacity = directResults.getInt("capacity");
                    int result_price = directResults.getInt("price");

                    // create an instance of Flight.
                    Flight flight = new Flight(result_fid, result_dayOfMonth, result_carrierId, result_flightNum,
                            result_originCity, result_destCity, result_time, result_capacity, result_price);
                    // and then initialize an itinerary instance and add it to itinerary list.
                    itineraries.add(new Itinerary(flight));
                    // itinerary index
                    itinaryId++;
                    // until the end of the row of the table.
                } while (directResults.next());
                directResults.close();
                // only happening if a user wanted indirect flight.
                if (!directFlight) {
                    List<Itinerary> indirectList = new ArrayList<>();
                    // to get n-k
                    int inDirectNum = numberOfItineraries - itinaryId;

                    if (inDirectNum > 0) {
                        // complete a table that we want.
                        inDirectStatement.clearParameters();
                        inDirectStatement.setInt(1, inDirectNum);
                        inDirectStatement.setString(2, originCity);
                        inDirectStatement.setString(3, destinationCity);
                        inDirectStatement.setInt(4, dayOfMonth);
                        inDirectStatement.setInt(5, dayOfMonth);

                        // now get a table.
                        ResultSet inDirectResults = inDirectStatement.executeQuery();

                        while (inDirectResults.next()) {
                            // this is for the first flight of the indirect flight
                            int first_result_fid = inDirectResults.getInt("first_fid");
                            int first_result_dayOfMonth = inDirectResults.getInt("first_day_of_month");
                            String first_result_carrierId = inDirectResults.getString("first_carrier_id");
                            String first_result_flightNum = inDirectResults.getString("first_flight_num");
                            String first_result_originCity = inDirectResults.getString("first_origin_city");
                            String first_result_destCity = inDirectResults.getString("first_dest_city");
                            int first_result_time = inDirectResults.getInt("first_actual_time");
                            int first_result_capacity = inDirectResults.getInt("first_capacity");
                            int first_result_price = inDirectResults.getInt("first_price");

                            // this is for the second flight of the indirect flight
                            int second_result_fid = inDirectResults.getInt("second_fid");
                            int second_result_dayOfMonth = inDirectResults.getInt("second_day_of_month");
                            String second_result_carrierId = inDirectResults.getString("second_carrier_id");
                            String second_result_flightNum = inDirectResults.getString("second_flight_num");
                            String second_result_originCity = inDirectResults.getString("second_origin_city");
                            String second_result_destCity = inDirectResults.getString("second_dest_city");
                            int second_result_time = inDirectResults.getInt("second_actual_time");
                            int second_result_capacity = inDirectResults.getInt("second_capacity");
                            int second_result_price = inDirectResults.getInt("second_price");

                            // Flight instance for the first flight
                            Flight first = new Flight(first_result_fid, first_result_dayOfMonth, first_result_carrierId, first_result_flightNum,
                                    first_result_originCity, first_result_destCity, first_result_time, first_result_capacity, first_result_price);
                            // Flight instance for the second flight
                            Flight second = new Flight(second_result_fid, second_result_dayOfMonth, second_result_carrierId, second_result_flightNum,
                                    second_result_originCity, second_result_destCity, second_result_time, second_result_capacity, second_result_price);

                            // initialize Itinerary with first and second of Flight instances. In the Itinerary constructor,
                            // total flight and fid of the first and second are all calculated.
                            itineraries.add(new Itinerary(first, second));
                        }
                        inDirectResults.close();
                    }


                }
                // compareTo method is overriden. This will check and compare total time.
                Collections.sort(itineraries);
                // print itineraries that we found.
                for (int i = 0; i < itineraries.size(); i++) {
                    Itinerary temp = itineraries.get(i);
                    sb.append("Itinerary " + i + ": " + temp.count + " flight(s), " + temp.totalTime + " minutes\n");
                    sb.append(temp.toString());
                }
                conn.commit();
                return sb.toString();

            } catch (SQLException e) {
                try{
                    if(isDeadLock(e)){
                        conn.rollback();
                        return transaction_search(originCity, destinationCity, directFlight,
                                dayOfMonth, numberOfItineraries);
                    }
                }catch(SQLException ex){
                    ex.printStackTrace();
                }
                e.printStackTrace();
                return "Failed to search\n";
            }
        } finally {
            checkDanglingTransaction();
        }
    }

    /**
     * Implements the book itinerary function.
     *
     * @param itineraryId ID of the itinerary to book. This must be one that is returned by search in
     *                    the current session.
     * @return If the user is not logged in, then return "Cannot book reservations, not logged in\n".
     * If the user is trying to book an itinerary with an invalid ID or without having done a
     * search, then return "No such itinerary {@code itineraryId}\n". If the user already has
     * a reservation on the same day as the one that they are trying to book now, then return
     * "You cannot book two flights in the same day\n". For all other errors, return "Booking
     * failed\n".
     * <p>
     * And if booking succeeded, return "Booked flight(s), reservation ID: [reservationId]\n"
     * where reservationId is a unique number in the reservation system that starts from 1 and
     * increments by 1 each time a successful reservation is made by any user in the system.
     */
    public String transaction_book(int itineraryId) {
        try {
            // check if a user logged in
            if (currentUser == null) {
                return "Cannot book reservations, not logged in\n";
                // check if a user searched itinerary before booking
            } else if (itineraries == null) {
                return "No such itinerary " + itineraryId + "\n";
                // another sanity check
            } else if (itineraryId < 0 || itineraryId >= itineraries.size()) {
                return "No such itinerary " + itineraryId + "\n";
            } else {

                // if everything's good,
                try {

                    conn.setAutoCommit(false);
                    // extract the itinerary for its information
                    Itinerary itBook = itineraries.get(itineraryId);
                    // capacity check for first flight
                    int capacityF1 = checkFlightCapacity(itBook.f1.fid);
                    int capacityF2;
                    capF1Statement.clearParameters();
                    capF1Statement.setInt(1, itBook.f1.fid);
                    ResultSet rs = capF1Statement.executeQuery();
                    rs.next();
                    int cap = rs.getInt("count");
                    if (capacityF1 - cap <= 0) {
                        conn.rollback();
                        return "Booking failed\n";
                    }
                    rs.close();
                    // capacity check for second flight
                    if (itBook.f2 != null) {
                        capF2Statement.clearParameters();
                        capF2Statement.setInt(1, itBook.f2.fid);
                        capacityF2 = checkFlightCapacity(itBook.f2.fid);
                        rs = capF1Statement.executeQuery();
                        rs.next();
                        cap = rs.getInt("count");
                        if (capacityF2 - cap <= 0) {
                            conn.rollback();
                            return "Booking failed\n";
                        }
                    }
                    rs.close();
                    // check if the user has booking in that specific day
                    checkDayStatement.clearParameters();
                    checkDayStatement.setString(1, currentUser);
                    checkDayStatement.setInt(2, itBook.f1.dayOfMonth);
                    rs = checkDayStatement.executeQuery();
                    rs.next();
                    int count = rs.getInt("count");
                    rs.close();
                    // if yes, close
                    if (count != 0) {
                        conn.rollback();
                        return "You cannot book two flights in the same day\n";
                    }

                    // now get a reservation id to use
                    int reservID;
                    reservIDStatement.clearParameters();
                    rs = reservIDStatement.executeQuery();
                    rs.next();
                    reservID = rs.getInt("count") + 1;
                    rs.close();
                    // complete the statement to insert into the reservation table
                    int price = itBook.f1.price;
                    resvBookingStatement.clearParameters();
                    resvBookingStatement.setInt(1, reservID);
                    resvBookingStatement.setInt(2, itBook.f1.fid);
                    if (itBook.f2 != null) {
                        resvBookingStatement.setInt(3, itBook.f2.fid);
                        price += itBook.f2.price;
                    } else {
                        resvBookingStatement.setNull(3, java.sql.Types.INTEGER);
                    }
                    resvBookingStatement.setInt(4, 0);
                    resvBookingStatement.setString(5, currentUser);
                    resvBookingStatement.setInt(6, itBook.f1.dayOfMonth);
                    resvBookingStatement.setInt(7, price);
                    resvBookingStatement.setInt(8, 0);

                    resvBookingStatement.executeUpdate();
                    conn.commit();
                    return "Booked flight(s), reservation ID: " + reservID + "\n";

                } catch (SQLException e) {
                    try{
                        if(isDeadLock(e)){
                            conn.rollback();
                            return transaction_book(itineraryId);
                        }
                    }catch(SQLException ex){
                        ex.printStackTrace();
                    }
                    e.printStackTrace();
                    return "Booking failed\n";

                }


            }

        } finally {
            checkDanglingTransaction();
        }
    }

    /**
     * Implements the pay function.
     *
     * @param reservationId the reservation to pay for.
     * @return If no user has logged in, then return "Cannot pay, not logged in\n" If the reservation
     * is not found / not under the logged in user's name, then return "Cannot find unpaid
     * reservation [reservationId] under user: [username]\n" If the user does not have enough
     * money in their account, then return "User has only [balance] in account but itinerary
     * costs [cost]\n" For all other errors, return "Failed to pay for reservation
     * [reservationId]\n"
     * <p>
     * If successful, return "Paid reservation: [reservationId] remaining balance:
     * [balance]\n" where [balance] is the remaining balance in the user's account.
     */
    public String transaction_pay(int reservationId) {
        if (currentUser == null) {
            return "Cannot pay, not logged in\n";
        }
        try {
            try {

                conn.setAutoCommit(false);
                // get user balance
                getUserStatement.clearParameters();
                getUserStatement.setString(1, currentUser);
                ResultSet userBalanceRes = getUserStatement.executeQuery();
                userBalanceRes.next();
                int balance = userBalanceRes.getInt("balance");
                userBalanceRes.close();
                // complete the statement to get the reservation information
                reservUserAndIDStatement.setInt(1, reservationId);
                reservUserAndIDStatement.setString(2, currentUser);
                ResultSet reservationRes = reservUserAndIDStatement.executeQuery();
                // if no result, close
                if (!reservationRes.next()) {
                    conn.rollback();
                    return "Cannot find unpaid reservation " + reservationId + " under user: " + currentUser + "\n";
                }
                // extract the price and paid of the reservation.
                int price = reservationRes.getInt("price");
                int paid = reservationRes.getInt("paid");
                if (price > balance) {
                    conn.rollback();
                    return "User has only " + balance + " in account but itinerary costs " + price + "\n";
                } else if (paid == 1) {
                    conn.rollback();
                    return "Cannot find unpaid reservation " + reservationId + " under user: " + currentUser + "\n";
                }
                reservationRes.close();
                // after paying, update to paid
                balance -= price;
                updatePaidStatement.clearParameters();
                updatePaidStatement.setString(1, currentUser);
                updatePaidStatement.setInt(2, reservationId);
                updatePaidStatement.executeUpdate();
                // update user balance
                updateBalanceStatement.clearParameters();
                updateBalanceStatement.setInt(1, balance);
                updateBalanceStatement.setString(2, currentUser);
                updateBalanceStatement.executeUpdate();
                conn.commit();
                return "Paid reservation: " + reservationId + " remaining balance: " + balance + "\n";

            } catch (SQLException e) {
                try{
                    if(isDeadLock(e)){
                        conn.rollback();
                        return transaction_pay(reservationId);
                    }
                }catch(SQLException ex){
                    ex.printStackTrace();
                }
                e.printStackTrace();
                return "Failed to pay for reservation " + reservationId + "\n";
            }

        } finally {
            checkDanglingTransaction();
        }
    }

    /**
     * Implements the reservations function.
     *
     * @return If no user has logged in, then return "Cannot view reservations, not logged in\n" If
     * the user has no reservations, then return "No reservations found\n" For all other
     * errors, return "Failed to retrieve reservations\n"
     * <p>
     * Otherwise return the reservations in the following format:
     * <p>
     * Reservation [reservation ID] paid: [true or false]:\n [flight 1 under the
     * reservation]\n [flight 2 under the reservation]\n Reservation [reservation ID] paid:
     * [true or false]:\n [flight 1 under the reservation]\n [flight 2 under the
     * reservation]\n ...
     * <p>
     * Each flight should be printed using the same format as in the {@code Flight} class.
     * @see Flight#toString()
     */
    public String transaction_reservations() {
        if (currentUser == null) {
            return "Cannot view reservations, not logged in\n";
        }
        try {
            // setup StrinbBuffer to make a string, and variables
            StringBuffer sb = new StringBuffer();
            boolean boolPaid;
            Itinerary resv;
            try {

                conn.setAutoCommit(false);
                // get a user's reservation
                reservStatement.clearParameters();
                reservStatement.setString(1, currentUser);
                ResultSet rs = reservStatement.executeQuery();
                // if no row, close
                if (!rs.next()) {
                    conn.rollback();
                    return "No reservations found\n";
                }
                do {
                    // get the itinerary information from the reservation
                    int id = rs.getInt("id");
                    int fid1 = rs.getInt("fid1");
                    int fid2 = rs.getInt("fid2");
                    int paid = rs.getInt("paid");
                    String username = rs.getString("username");
                    int price = rs.getInt("price");
                    if (paid == 0) {
                        boolPaid = false;
                    } else {
                        boolPaid = true;
                    }
                    // get information for flight 1
                    reservFlightStatement.clearParameters();
                    reservFlightStatement.setInt(1, fid1);
                    ResultSet first = reservFlightStatement.executeQuery();
                    first.next();
                    int first_result_dayOfMonth = first.getInt("day_of_month");
                    String first_result_carrierId = first.getString("carrier_id");
                    String first_result_flightNum = first.getString("flight_num");
                    String first_result_originCity = first.getString("origin_city");
                    String first_result_destCity = first.getString("dest_city");
                    int first_result_time = first.getInt("actual_time");
                    int first_result_capacity = first.getInt("capacity");
                    int first_result_price = first.getInt("price");
                    // if there is only one flight, instantiate Flight and itinerary with those information found
                    if (fid2 == 0) {
                        resv = new Itinerary(new Flight(fid1, first_result_dayOfMonth,
                                first_result_carrierId, first_result_flightNum, first_result_originCity,
                                first_result_destCity, first_result_time, first_result_capacity, first_result_price));
                        // if there is second flight, get information for it just like above
                    } else {
                        reservFlightStatement.clearParameters();
                        reservFlightStatement.setInt(1, fid2);
                        ResultSet second = reservFlightStatement.executeQuery();
                        second.next();
                        int second_result_dayOfMonth = second.getInt("day_of_month");
                        String second_result_carrierId = second.getString("carrier_id");
                        String second_result_flightNum = second.getString("flight_num");
                        String second_result_originCity = second.getString("origin_city");
                        String second_result_destCity = second.getString("dest_city");
                        int second_result_time = second.getInt("actual_time");
                        int second_result_capacity = second.getInt("capacity");
                        int second_result_price = second.getInt("price");

                        resv = new Itinerary(new Flight(fid1, first_result_dayOfMonth,
                                first_result_carrierId, first_result_flightNum, first_result_originCity,
                                first_result_destCity, first_result_time, first_result_capacity, first_result_price),
                                new Flight(fid2, second_result_dayOfMonth,
                                        second_result_carrierId, second_result_flightNum, second_result_originCity,
                                        second_result_destCity, second_result_time, second_result_capacity, second_result_price)

                        );
                    }

                    sb.append("Reservation " + id + " paid: " + boolPaid + ":\n" + resv.toString());
                    // do it until rs.next() false.
                } while (rs.next());

                conn.commit();
                return sb.toString();
            } catch (SQLException e) {
                try{
                    if(isDeadLock(e)){
                        conn.rollback();
                        return transaction_reservations();
                    }
                }catch(SQLException ex){
                    ex.printStackTrace();
                }
                e.printStackTrace();
                return "Failed to retrieve reservations\n";
            }

        } finally {
            checkDanglingTransaction();
        }
    }

    /**
     * Implements the cancel operation.
     *
     * @param reservationId the reservation ID to cancel
     * @return If no user has logged in, then return "Cannot cancel reservations, not logged in\n" For
     * all other errors, return "Failed to cancel reservation [reservationId]\n"
     * <p>
     * If successful, return "Canceled reservation [reservationId]\n"
     * <p>
     * Even though a reservation has been canceled, its ID should not be reused by the system.
     */
    public String transaction_cancel(int reservationId) {
        if (currentUser == null) {
            return "Cannot cancel reservations, not logged in\n";
        }
        try {

            try {
                conn.setAutoCommit(false);
                // get a user's reservation information
                reserCancelStatement.clearParameters();
                reserCancelStatement.setString(1, currentUser);
                reserCancelStatement.setInt(2, reservationId);
                ResultSet res = reserCancelStatement.executeQuery();
                if (!res.next()) {
                    conn.rollback();
                    return "Failed to cancel reservation " + reservationId + "\n";
                }
                // extract "paid" and "price" of the reservation
                int paid = res.getInt("paid");
                int price = res.getInt("price");
                // get a user's balance
                getUserStatement.clearParameters();
                getUserStatement.setString(1, currentUser);
                ResultSet userRes = getUserStatement.executeQuery();
                userRes.next();
                int balance = userRes.getInt("balance");

                // update once it is cancelled.
                cancelledStatement.clearParameters();
                cancelledStatement.setInt(1, reservationId);
                cancelledStatement.setString(2, currentUser);
                cancelledStatement.executeUpdate();
                // if it was already paid, give the money back so update the balance.
                if (paid == 1) {
                    balance += price;
                    updateBalanceStatement.clearParameters();
                    updateBalanceStatement.setInt(1, balance);
                    updateBalanceStatement.setString(2, currentUser);
                    updateBalanceStatement.executeUpdate();

                }
                // process it.
                conn.commit();
                return "Canceled reservation " + reservationId + "\n";
            } catch (SQLException e) {
                try{
                    if(isDeadLock(e)){
                        conn.rollback();
                        return transaction_cancel(reservationId);
                    }
                }catch(SQLException ex){
                    ex.printStackTrace();
                }
                e.printStackTrace();
                return "Failed to cancel reservation " + reservationId + "\n";
            }

        } finally {
            checkDanglingTransaction();
        }
    }

    /**
     * Example utility function that uses prepared statements
     */
    private int checkFlightCapacity(int fid) throws SQLException {
        checkFlightCapacityStatement.clearParameters();
        checkFlightCapacityStatement.setInt(1, fid);
        ResultSet results = checkFlightCapacityStatement.executeQuery();
        results.next();
        int capacity = results.getInt("capacity");
        results.close();

        return capacity;
    }

    /**
     * Throw IllegalStateException if transaction not completely complete, rollback.
     */
    private void checkDanglingTransaction() {
        try {
            try (ResultSet rs = tranCountStatement.executeQuery()) {
                rs.next();
                int count = rs.getInt("tran_count");
                if (count > 0) {
                    throw new IllegalStateException(
                            "Transaction not fully commit/rollback. Number of transaction in process: " + count);
                }
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database error", e);
        }
    }

    private static boolean isDeadLock(SQLException ex) {
        return ex.getErrorCode() == 1205;
    }

    /**
     * A class to store flight information.
     */
    class Flight {
        public int fid;
        public int dayOfMonth;
        public String carrierId;
        public String flightNum;
        public String originCity;
        public String destCity;
        public int time;
        public int capacity;
        public int price;

        public Flight(int fid, int dayOfMonth, String carrierId, String flightNum, String originCity,
                      String destCity, int time, int capacity, int price) {
            this.fid = fid;
            this.dayOfMonth = dayOfMonth;
            this.carrierId = carrierId;
            this.flightNum = flightNum;
            this.originCity = originCity;
            this.destCity = destCity;
            this.time = time;
            this.capacity = capacity;
            this.price = price;
        }

        @Override
        public String toString() {
            return "ID: " + fid + " Day: " + dayOfMonth + " Carrier: " + carrierId + " Number: "
                    + flightNum + " Origin: " + originCity + " Dest: " + destCity + " Duration: " + time
                    + " Capacity: " + capacity + " Price: " + price;
        }
    }

    // this is mainly for comparison purpose.
    class Itinerary implements Comparable<Itinerary> {
        public Flight f1;
        public Flight f2;
        public int totalTime;
        public int count;

        // for direct flight
        public Itinerary(Flight f1) {
            this.f1 = f1;
            this.totalTime = f1.time;
            this.count = 1;
        }

        // for indirect flight
        public Itinerary(Flight f1, Flight f2) {
            this.f1 = f1;
            this.f2 = f2;
            this.totalTime = f1.time + f2.time;
            this.count = 2;
        }

        @Override
        public String toString() {
            if (count == 1) {
                return this.f1.toString() + "\n";
            } else {
                return this.f1.toString() + "\n" + this.f2.toString() + "\n";
            }
        }

        // to compare variables of itinerary
        @Override
        public int compareTo(Itinerary other) {
            int time = this.totalTime - other.totalTime;
            if (time != 0) {
                return time;
            } else {
                int fid1 = this.f1.fid - other.f1.fid;
                if (fid1 == 0 && this.f2 != null && other.f2 != null) {
                    return this.f2.fid - other.f2.fid;
                }
                return fid1;
            }

        }
    }


}
