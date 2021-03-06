package com.dscoursework.client;

import com.dscoursework.common.InvalidMatrixException;
import com.dscoursework.grpc.BlockMultServiceGrpc;
import com.dscoursework.grpc.BlockMultServiceGrpc.BlockMultServiceBlockingStub;
import com.dscoursework.grpc.MatrixRequest;
import com.dscoursework.grpc.MatrixResponse;
import com.dscoursework.common.MatrixOperation;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.HashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;

import static com.google.common.math.IntMath.isPowerOfTwo;

@Service
public class GRPCClientService {

	long deadline;

	@Autowired
	private Environment env;

	//@Value("${com.dscoursework.grpc.client.stubPorts}")
	private int[] stubPorts = {8081,8082,8083,8084,8085,8086,8087,8088};

	@Value("${com.dscoursework.grpc.client.serverAddress}")
	private String serverAddress;

	private ManagedChannel[] channels;
	private BlockMultServiceBlockingStub[] stubs;
	private BlockingQueue<Integer> stubIndices = new LinkedBlockingQueue<>(stubPorts.length);

	@PostConstruct
	public void init() throws InterruptedException {
		channels = createChannels();
		stubs = createStubs();
	}

	@PreDestroy
	public void destroy() {
	    for(ManagedChannel channel : channels) {
			channel.shutdown();
		}
	}

	/**
	 * Takes the indices of the stubs that have not been used recently and adds them to the back of the queue.
	 */
	private int[] takeStubIndices(int num) throws InterruptedException {
		int[] indices = new int[num];
		for(int i = 0; i < num; i++) {
			indices[i] = this.stubIndices.take();
			this.stubIndices.add(indices[i]);
		}
		return indices;
	}

	private BlockMultServiceBlockingStub[] createStubs() {
		BlockMultServiceBlockingStub[] stubs = new BlockMultServiceBlockingStub[stubPorts.length];

		for(int i =0; i < channels.length; i++) {
			stubs[i] = BlockMultServiceGrpc.newBlockingStub(channels[i]);
		}

		for(int i = 0; i < stubPorts.length; i++) {
			stubIndices.add(i);
		}

		return stubs;
	}

	private ManagedChannel[] createChannels() {
		ManagedChannel[] chans = new ManagedChannel[stubPorts.length];
		System.out.println("Connecting to server at: " + serverAddress);

		for(int i =0; i < stubPorts.length; i++) {
			chans[i] = ManagedChannelBuilder.forAddress(serverAddress, stubPorts[i])
					.keepAliveWithoutCalls(true)
					.usePlaintext()
					.build();
		}
		return chans;
	}


	//private static final int MAX = 4;

	public String multiplyMatrixFiles(String matrixStringA, String matrixStringB, long deadline) throws InvalidMatrixException, ExecutionException, InterruptedException {
		int[][] A = stringToArray(matrixStringA);
		int[][] B = stringToArray(matrixStringB);
		MatrixOperation.encodeMatrix(A);
		MatrixOperation.encodeMatrix(B);
		long startTime = System.nanoTime();

		int[][] MatrixBlock = multiplyMatrixBlock(A, B, deadline);
		long endTime = System.nanoTime();
		long time = endTime-startTime;
		System.out.println("Time spend  "+time+ " ns" );
		return MatrixOperation.encodeMatrix(MatrixBlock);
	}


	public String addMatrixFiles(String matrixStringA, String matrixStringB, long deadline) throws InvalidMatrixException, ExecutionException, InterruptedException {
		int[][] A = stringToArray(matrixStringA);
		int[][] B = stringToArray(matrixStringB);
		long startTime = System.nanoTime();
		int[][] MatrixBlock = addBlock(A, B,1);
		long endTime = System.nanoTime();
		long time = endTime-startTime;
		System.out.println("Using 4 servers" );
		System.out.println("Time spend  "+time+ " ns" );
		return MatrixOperation.encodeMatrix(MatrixBlock);
	}

	public static int[][] stringToArray(String matrixString) throws InvalidMatrixException {
		// convert matrix string to lines and columns
		String[] lines = matrixString.trim().split("\n");
		String[] columns = lines[0].trim().split(" ");

		// init the matrix array
		int[][] matrixArray = new int[lines.length][columns.length];

		if(lines.length < 1 || columns.length < 1) {
			throw new InvalidMatrixException("Invalid matrix:\n\n" + matrixString + "\n\nMatrix must have rows and columns", new Error("matrix must have rows and columns"));
		}

		if(lines.length != columns.length) {
			throw new InvalidMatrixException("Invalid matrix:\n\n" + matrixString + "\n\nMatrix must same number of rows and columns", new Error("matrix must same number of rows and columns"));
		}

		if(!isPowerOfTwo(lines.length) || !isPowerOfTwo(columns.length)){
			throw new InvalidMatrixException("Invalid matrix:\n\n" + matrixString + "\n\nMatrix row and column size must be a power of 2", new Error("matrix row and column size must be a power of 2"));
		}

		try {
			// loop through each matrix value and assign to matrixArray
			for (int i = 0; i < lines.length; i++) {
				String[] matrixValues = lines[i].trim().split(" ");
				if(matrixValues.length != columns.length) {
					throw new InvalidMatrixException("Invalid matrix:\n\n" + matrixString + "\n\nMatrix row length not equal", new Error("matrix row length not equal"));
				}
				for (int j = 0; j < matrixValues.length; j++) {
					matrixArray[i][j] = Integer.parseInt(matrixValues[j]);
				}
			}
		} catch (NumberFormatException|ArrayIndexOutOfBoundsException e) {
			throw new InvalidMatrixException("Invalid matrix:\n\n" + matrixString, e);
		}

		return matrixArray;
	}

	/**
	 * Add integer matrices via gRPC
	 */
	private HashMap<String,int[][]> splitBlocks(int[][] A, int[][] B) {

		int MAX = A.length;
		int bSize = MAX/2;

		int[][] A1 = new int[MAX][MAX];
		int[][] A2 = new int[MAX][MAX];
		int[][] B1 = new int[MAX][MAX];
		int[][] B2 = new int[MAX][MAX];
		int[][] C1 = new int[MAX][MAX];
		int[][] C2 = new int[MAX][MAX];
		int[][] D1 = new int[MAX][MAX];
		int[][] D2 = new int[MAX][MAX];

		for (int i = 0; i < bSize; i++)
		{
			for (int j = 0; j < bSize; j++)
			{
				A1[i][j]=A[i][j];
				A2[i][j]=B[i][j];
			}
		}
		for (int i = 0; i < bSize; i++)
		{
			for (int j = bSize; j < MAX; j++)
			{
				B1[i][j-bSize]=A[i][j];
				B2[i][j-bSize]=B[i][j];
			}
		}
		for (int i = bSize; i < MAX; i++)
		{
			for (int j = 0; j < bSize; j++)
			{
				C1[i-bSize][j]=A[i][j];
				C2[i-bSize][j]=B[i][j];
			}
		}
		for (int i = bSize; i < MAX; i++)
		{
			for (int j = bSize; j < MAX; j++)
			{
				D1[i-bSize][j-bSize]=A[i][j];
				D2[i-bSize][j-bSize]=B[i][j];
			}
		}

		HashMap<String, int[][]> blocks = new HashMap<>();
		blocks.put("A1", A1);
		blocks.put("A2", A2);
		blocks.put("B1", B1);
		blocks.put("B2", B2);
		blocks.put("C1", C1);
		blocks.put("C2", C2);
		blocks.put("D1", D1);
		blocks.put("D2", D2);

		return blocks;
	}


	private int[][] addBlock(int A[][], int B[][], int stubIndex) {
		MatrixRequest request = generateRequest(A, B);
		MatrixResponse matrixAddResponse = this.stubs[stubIndex].addBlock(request);
		int[][] summedMatrix = MatrixOperation.decodeMatrix(matrixAddResponse.getMatrix());
		return summedMatrix;
	}


	/**
	 * Multiply integer matrices via gRPC
	 */
	private int[][] multiplyBlock(int A[][], int B[][], int stubIndex) {
		MatrixRequest request = generateRequest(A, B);
		MatrixResponse matrixMultiplyResponse = this.stubs[stubIndex].multiplyBlock(request);
		int[][] multipliedMatrix = MatrixOperation.decodeMatrix(matrixMultiplyResponse.getMatrix());
		return multipliedMatrix;
	}


	/**
	 *  encode the matrices and return a MatrixRequest object
	 */

	private static MatrixRequest generateRequest(int A[][], int B[][]) {
		String matrixA = MatrixOperation.encodeMatrix(A);
		String matrixB = MatrixOperation.encodeMatrix(B);

		MatrixRequest request = MatrixRequest.newBuilder()
				.setMatrixA(matrixA)
				.setMatrixB(matrixB)
				.build();

		return request;
	}


	/**
	 * Multiplies matrices using addBlock and multiplyBlock
	 * From BlockMult.java
	 */
	private int caculateServer(long deadline, long footprint){
		long numBlockCalls = 11L;
		return (int) Math.ceil((float)footprint*(float)numBlockCalls/(float)deadline);
	}

	private int[][] multiplyMatrixBlock(int[][] A, int[][] B, long deadline) throws InterruptedException, ExecutionException {

		// split matrix blocks into smaller blocks
		HashMap<String, int[][]> blocks = splitBlocks(A, B);

		// get first gRPC server stub
		int firstStubIndex = takeStubIndices(1)[0];
		long numBlockCalls = 11L;
		// footprint algorithm to see how long first call takes
		long Time1 = System.nanoTime();

		// CompletableFuture enables asynchronous calls to the multiplyBlock function
		CompletableFuture<int[][]> A1A2Future = CompletableFuture.supplyAsync(() -> multiplyBlock(blocks.get("A1"), blocks.get("A2"), firstStubIndex));

		// This will wait for the async function to complete before continuing
		int[][] A1A2 = A1A2Future.get();
		long Time2 = System.nanoTime();
		long footprint= Time2-Time1;
		System.out.println("The foot print of one block:"+ footprint +" ns");
		int numberServer = caculateServer(deadline,footprint);
		numberServer = numberServer <= 8 ? numberServer : 8;

		System.out.println("Using "+ numberServer + " servers for  calculation");

		// take the least recently used stub indices for this workload to reduce traffic
		int[] indices = takeStubIndices(numberServer);

		// a thread safe index queue so each the async functions are evenly spread along the stubs
		BlockingQueue<Integer> indexQueue = new LinkedBlockingQueue<>((int) numBlockCalls);

		int i = 0;
		while(indexQueue.size() != numBlockCalls) {
		    if(indices.length == i) {
		        i = 0;
			}
			indexQueue.add(indices[i]);
			i++;
		}

		// a series of asynchronous calls to the gRPC blocking calls
		// does run asynchronously as you can sometimes see the addblock function calls before some multiplication call
		CompletableFuture<int[][]> B1C2 = CompletableFuture.supplyAsync(() -> {
			try {
				return multiplyBlock(blocks.get("B1"), blocks.get("C2"), indexQueue.take());
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			return null;
		});

		CompletableFuture<int[][]> A1B2 = CompletableFuture.supplyAsync(() -> {
			try {
				return multiplyBlock(blocks.get("A1"), blocks.get("B2"), indexQueue.take());
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			return null;
		});

		CompletableFuture<int[][]> B1D2 = CompletableFuture.supplyAsync(() -> {
			try {
				return multiplyBlock(blocks.get("B1"), blocks.get("D2"), indexQueue.take());
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			return null;
		});

		CompletableFuture<int[][]> C1A2 = CompletableFuture.supplyAsync(() -> {
			try {
				return multiplyBlock(blocks.get("C1"), blocks.get("A2"), indexQueue.take());
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			return null;
		});

		CompletableFuture<int[][]> D1C2 = CompletableFuture.supplyAsync(() -> {
			try {
				return multiplyBlock(blocks.get("D1"), blocks.get("C2"), indexQueue.take());
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			return null;
		});

		CompletableFuture<int[][]> C1B2 = CompletableFuture.supplyAsync(() -> {
			try {
				return multiplyBlock(blocks.get("C1"), blocks.get("B1"), indexQueue.take());
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			return null;
		});

		CompletableFuture<int[][]> D1D2 = CompletableFuture.supplyAsync(() -> {
			try {
				return multiplyBlock(blocks.get("D1"), blocks.get("D2"), indexQueue.take());
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			return null;
		});

		CompletableFuture<int[][]> A3 = CompletableFuture.supplyAsync(() -> {
			try {
				return addBlock(A1A2, B1C2.get(), indexQueue.take());
			} catch (InterruptedException | ExecutionException e) {
				e.printStackTrace();
			}
			return null;
		});

		CompletableFuture<int[][]> B3 = CompletableFuture.supplyAsync(() -> {
			try {
				return addBlock(A1B2.get(), B1D2.get(), indexQueue.take());
			} catch (InterruptedException | ExecutionException e) {
				e.printStackTrace();
			}
			return null;
		});

		CompletableFuture<int[][]> C3 = CompletableFuture.supplyAsync(() -> {
			try {
				return addBlock(C1A2.get(), D1C2.get(), indexQueue.take());
			} catch (InterruptedException | ExecutionException e) {
				e.printStackTrace();
			}
			return null;
		});

		CompletableFuture<int[][]> D3 = CompletableFuture.supplyAsync(() -> {
			try {
				return addBlock(C1B2.get(), D1D2.get(), indexQueue.take());
			} catch (InterruptedException | ExecutionException e) {
				e.printStackTrace();
			}
			return null;
		});

		// join the remote calculations back together

		int[][] res = joinBlocks(A3.get(), B3.get(), C3.get(), D3.get());
		return res;
	}

	/**
	 * Joins the blocks back together
	 * From BlockMult.java
	 */
	private int[][] joinBlocks(int[][] A3, int[][] B3, int[][] C3, int[][] D3) {
		int MAX = A3.length;
		int bSize = MAX/2;
		int[][] res = new int[MAX][MAX];

		for (int i = 0; i < bSize; i++)
		{
			for (int j = 0; j < bSize; j++)
			{
				res[i][j]=A3[i][j];
			}
		}
		for (int i = 0; i < bSize; i++)
		{
			for (int j = bSize; j < MAX; j++)
			{
				res[i][j]=B3[i][j-bSize];
			}
		}
		for (int i = bSize; i < MAX; i++)
		{
			for (int j = 0; j < bSize; j++)
			{
				res[i][j]=C3[i-bSize][j];
			}
		}
		for (int i = bSize; i < MAX; i++)
		{
			for (int j = bSize; j < MAX; j++)
			{
				res[i][j]=D3[i-bSize][j-bSize];
			}
		}
		return res;
	}


}
