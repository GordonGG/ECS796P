package com.dscoursework.server;

import com.dscoursework.grpc.BlockMultServiceGrpc;
import com.dscoursework.grpc.MatrixRequest;
import com.dscoursework.grpc.MatrixResponse;
import com.dscoursework.common.MatrixOperation;
import io.grpc.stub.StreamObserver;

import javax.el.MethodNotFoundException;

public class GrpcServerService extends BlockMultServiceGrpc.BlockMultServiceImplBase {

	int threadNumber;

	public GrpcServerService(int threadNumber) {
		this.threadNumber = threadNumber;
	}

	@Override
	public void addBlock(MatrixRequest request, StreamObserver<MatrixResponse> responseObserver) {
		System.out.println("addBlock called on server "+ threadNumber);
		requestHandler(request, responseObserver, "add");
	}

	@Override
	public void multiplyBlock(MatrixRequest request, StreamObserver<MatrixResponse> responseObserver) {
		System.out.println("multiplyBlock called on server " + threadNumber);
		requestHandler(request, responseObserver, "multiply");
	}

	/**
	 * Handles the gRPC request for both addBlock and multiplyBlock methods
	 */
	private void requestHandler(MatrixRequest request, StreamObserver<MatrixResponse> responseObserver, String operation) throws MethodNotFoundException {

		// decode matrixA and matrixB from the request
		int[][] decodedMatrixA = MatrixOperation.decodeMatrix(request.getMatrixA());
		int[][] decodedMatrixB = MatrixOperation.decodeMatrix(request.getMatrixB());

		// define the matrix operation result to be of size MAX, MAX
		int[][] result;

		switch(operation) {
			case "add":
				result = addMatrices(decodedMatrixA, decodedMatrixB);
				break;
			case "multiply":
				result = multiplyMatrices(decodedMatrixA, decodedMatrixB);
				break;
			default:
				System.out.println("Cannot recognise operation: " + operation);
				throw new MethodNotFoundException("Couldn't find method: " + operation);
		}

		// encode the resultant matrix as a string
		String encodedMatrix = MatrixOperation.encodeMatrix(result);

		// generate the matrix response object
		MatrixResponse response = MatrixResponse.newBuilder()
			.setMatrix(encodedMatrix)
			.build();

		// send response of gRPC
		responseObserver.onNext(response);
		responseObserver.onCompleted();
	}

	/**
	 * Sums two matrices
	 */
	private static int[][] addMatrices(int[][] matrixA, int[][]matrixB) {

		int MAX = matrixA.length;
		int[][] result = new int[MAX][MAX];

		for (int i=0; i<result.length; i++) {
			for (int j=0; j < result.length; j++) {
				result[i][j] = matrixA[i][j] + matrixB[i][j];
			}
		}
		return result; 
	}

	/**
	 * Multiply two matrices together
	 */
	private static int[][] multiplyMatrices(int A[][], int B[][]) {

		int MAX = A.length;
		int blockSize = MAX/2;
		int C[][]= new int[MAX][MAX];


        for(int i=0;i<blockSize;i++){
            for(int j=0;j<blockSize;j++){
                for(int k=0;k<blockSize;k++){
                    C[i][j]+=(A[i][k]*B[k][j]);
                }
            }
        }

        return C;
	}
}
