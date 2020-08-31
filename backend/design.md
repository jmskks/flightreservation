My database design has two tables: USERS and RESERVATIONS. After learning how Hash works I realized that I need to have 
"hash" and "salt" as attributes in USERS table to make it work. In RESERVATIONS table, initially I had its ID, FID1 FID2 
PAID USERNAME. But as I go alone, I realized that I should have DAY attribute so that I can find a user and their all 
reservations on a certain day to check if there is another booking on the same day. Having PRICE attribute is also 
useful for "pay" transaction so that it doesn't have to look up the price of each itinerary every time a user is attempt 
to pay them. And also I added "CANCELLED" attribute to keep track of cancelled itinerary. Since we can't use 
the ReservationID that was used in the past even though it was cancelled, I chose to leave the reservation and only 
updates its CANCELLED attribute, rather than deleting the reservation in the RESERVATIONS table. 
I was thinking about creating a itinerary table as well, but realized that the list of itineraries that a user requested 
only lasts at the time requested, deleted and reset after log out. So I figured it would be better to have the list in the 
local than Azure server. As for the persisted data, I think every data related to USERS are persisted. So an example of non-persisted data would be itineraries that are searched by a user through search commmand. 