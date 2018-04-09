package ru.ifmo.se;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class Server extends Thread {
    //Серверный модуль должен реализовывать все функции управления коллекцией
    //в интерактивном режиме, кроме отображения текста в соответствии с сюжетом предметной области.
    private static DatagramSocket serverSocket;
    private static final int sizeOfPacket = 5000;
    protected static SortedSet<Person> collec = Collections.synchronizedSortedSet(new TreeSet<Person>());

    @Override
    public void run() {
        try {
            serverSocket = new DatagramSocket(4718, InetAddress.getByName("localhost"));
            System.out.println(serverSocket.toString());
            System.out.println(serverSocket.getLocalPort());
            System.out.println("Server is now running.");
            DatagramPacket fromClient = new DatagramPacket(new byte[sizeOfPacket], sizeOfPacket);
            while (true) {
                serverSocket.receive(fromClient);
                Connection connec = new Connection(serverSocket, fromClient);
            }
        } catch (UnknownHostException | SocketException e){
            System.out.println("Server is not listening.");
            e.printStackTrace();
        } catch (IOException e){
            System.out.println("Can not receive datagramPacket.");
            e.printStackTrace();
        }
    }
}

class Connection extends Thread {
    private DatagramSocket client;
    private DatagramPacket packet;
    private BufferedReader fromClient;
    private InetAddress address;
    private int clientPort;
    private final static String filename = System.getenv("FILENAME");
    private final static String currentdir = System.getProperty("user.dir");
    private static String filepath;
    private static File file;
    private ReentrantLock locker = new ReentrantLock();

    Connection(DatagramSocket serverSocket, DatagramPacket packetFromClient){
        Connection.filemaker();
        this.packet = packetFromClient;
        this.address = packetFromClient.getAddress();
        this.clientPort = packetFromClient.getPort();
        this.client = serverSocket;
        fromClient = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(this.packet.getData())));
        this.start();
    }

    private static void filemaker(){
        if (currentdir.startsWith("/")) {
            filepath = currentdir + "/" + filename;
        } else
            filepath = currentdir + "\\" + filename;
        file = new File(filepath);
    }

    public void run(){
        try {
            this.load();
        } catch (IOException e) {
            System.out.println("Exception while trying to load collection.\n" + e.toString());
        }
        System.out.println("Client " + address + " " + clientPort + " has connected to server.");
        DatagramPacket packetFromClient = this.packet;
        byte[] b = packetFromClient.getData();
        System.out.println(b[0]);
        ByteArrayInputStream byteStream = new ByteArrayInputStream(packetFromClient.getData());
        Scanner sc = new Scanner(byteStream);
        String command = sc.nextLine();
        System.out.println("Command from client: " + command);
        try {
            client.send(this.createPacket("You've connected to the server.\n"));
        } catch (IOException e){
            System.out.println("Can not send packet.");
        }

        while(true) {
            try {
                client.receive(packetFromClient);
                byteStream = new ByteArrayInputStream(packetFromClient.getData());
                sc = new Scanner(byteStream);
                command = sc.nextLine();
                System.out.println("Command from client: " + command);
                try {
                    switch (command) {
                        case "data_request":
                            this.giveCollection();
                            break;
                        case "save":
                            this.clear();
                            this.getCollection();
                            break;
                        case "qw":
                            this.getCollection();
                        case "q":
                            this.quit();
                            break;
                        case "load_file":
                            this.load();
                            client.send(this.createPacket("\n"));
                            break;
                        case "save_file":
                            this.save();
                            break;
                        default:
                            client.send(this.createPacket("Not valid command. Try one of those:\nhelp - get help;\nclear - clear the collection;" +
                                    "\nload - load the collection again;\nadd {element} - add new element to collection;" +
                                    "\nremove_greater {element} - remove elements greater than given;\n" +
                                    "show - show the collection;\nquit - quit;\n"));
                    }
                    byteStream.close();
                }catch (NullPointerException e){
                    System.out.println("Null command received.");
                }
            } catch (IOException e) {
                System.out.println("Connection with the client is lost.");
                System.out.println(e.toString());
                try {
                    fromClient.close();
                    client.close();
                } catch (IOException ee){
                    System.out.println("Exception while trying to close client's streams.");
                }
                return;
            }
        }
    }

    private DatagramPacket createPacket(String string){
        ByteArrayOutputStream toClient = new ByteArrayOutputStream();
        try {
            toClient.write(string.getBytes());
            toClient.close();
        } catch (IOException e){
            System.out.println("Can not create packet.");
        }
        DatagramPacket datagramPacket = new DatagramPacket(toClient.toByteArray(), toClient.size(), address, clientPort);
        return datagramPacket;
    }

    private void load() throws IOException {
        locker.lock();
        try (Scanner sc = new Scanner(file)) {
            StringBuilder tempString = new StringBuilder();
            tempString.append('[');
            sc.useDelimiter("}\\{");
            while (sc.hasNext()) {
                tempString.append(sc.next());
                if (sc.hasNext())
                    tempString.append("},{");
            }
            sc.close();
            JSONArray jsonArray = new JSONArray(tempString.append(']').toString());
            try {
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject jsonObject = jsonArray.getJSONObject(i);
                    String jsonObjectAsString = jsonObject.toString();
                    Server.collec.add(JsonConverter.jsonToObject(jsonObjectAsString, Known.class));
                }
                System.out.println("Connection has been loaded.");
            } catch (NullPointerException e) {
                try {
                    client.send(this.createPacket("File is empty.\n"));
                } catch (IOException ee){
                    System.out.println("Can not send packet.");
                }
            }
        } catch (FileNotFoundException e) {
            try {
                client.send(this.createPacket("Collection can not be loaded.\nFile "+filename+" is not accessible: it does not exist or permission denied.\n"));
            } catch (IOException ee){
                System.out.println("Can not send packet.");
            }
            e.printStackTrace();
        }
        locker.unlock();
    }

    private void getCollection() throws IOException{
        locker.lock();
        try {
            DatagramPacket packet = new DatagramPacket(new byte[10000], 10000);
            client.receive(packet);
            ByteArrayInputStream byteStream = new ByteArrayInputStream(packet.getData());
            ObjectInputStream objectInputStream = new ObjectInputStream(new BufferedInputStream(byteStream));
            Person person;
            while ((person = (Person) objectInputStream.readObject()) != null) {
                Server.collec.add(person);
            }
            client.send(this.createPacket("Collection has been saved on server.\n"));
            byteStream.close();
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
        locker.unlock();
    }

    private void quit() throws IOException {
        fromClient.close();
        client.close();
        System.out.println("Client has disconnected.");
    }

    private void save(){
        locker.lock();
        try {
            Writer writer = new FileWriter(file);
            //
            //Server.collec.forEach(person -> writer.write(Connection.objectToJson(person)));
            for (Person person: Server.collec){
                writer.write(JsonConverter.objectToJson(person));
            }
            writer.close();
            System.out.println("Collection has been saved.");
            client.send(this.createPacket("Collection has been saved to file.\n"));
        } catch (IOException e) {
            System.out.println("Collection can not be saved.\nFile "+filename+" is not accessible: it does not exist or permission denied.");
            e.printStackTrace();
        }
        locker.unlock();
    }

    public static void saveOnQuit(){
        try {
            Writer writer = new FileWriter(file);
            //
            //Server.collec.forEach(person -> writer.write(Connection.objectToJson(person)));
            for (Person person: Server.collec){
                writer.write(JsonConverter.objectToJson(person));
            }
            writer.close();
            System.out.println("Collection has been saved.");
        } catch (IOException e) {
            System.out.println("Collection can not be saved.\nFile "+filename+" is not accessible: it does not exist or permission denied.");
            e.printStackTrace();
        }
    }

    private void giveCollection(){
        locker.lock();
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream(10000);
        try {
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(new BufferedOutputStream(byteStream));
            for (Person person : Server.collec) {
                objectOutputStream.writeObject(person);
            }
            client.send(this.createPacket(" Collection copy has been loaded on client.\n"));
            byte[] bytes = byteStream.toByteArray();
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, address, clientPort);
            client.send(packet);
            byteStream.close();
        } catch (IOException e) {
            System.out.println("Can not send collection to server.");
            e.printStackTrace();
        }
        locker.unlock();
    }

    private void showCollection() {
        if (Server.collec.isEmpty())
            System.out.println("Collection is empty.");
        for (Person person : Server.collec) {
            System.out.println(person.toString());
        }
    }

    private void clear() {
        Server.collec.clear();
    }
}