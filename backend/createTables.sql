CREATE TABLE USERS (
username varchar(20), 
hash VARBINARY(100),
salt VARBINARY(100),
balance int,
primary key(username)
);


CREATE TABLE RESERVATIONS(
id int,
fid1 int,
fid2 int,
paid int,
username varchar(20),
day int,
price int,
cancelled int,
primary key(id),
foreign key(username) references USERS(username)

);

