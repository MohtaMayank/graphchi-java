package edu.cmu.graphchi.toolkits.collaborative_filtering.algorithms;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.apache.commons.math3.distribution.NormalDistribution;

import edu.cmu.graphchi.ChiLogger;
import edu.cmu.graphchi.ChiVertex;
import edu.cmu.graphchi.GraphChiContext;
import edu.cmu.graphchi.GraphChiProgram;
import edu.cmu.graphchi.engine.GraphChiEngine;
import edu.cmu.graphchi.engine.VertexInterval;
import edu.cmu.graphchi.preprocessing.FastSharder;
import edu.cmu.graphchi.toolkits.collaborative_filtering.utils.DataSetDescription;
import edu.cmu.graphchi.toolkits.collaborative_filtering.utils.IO;
import edu.cmu.graphchi.toolkits.collaborative_filtering.utils.ModelParameters;
import edu.cmu.graphchi.toolkits.collaborative_filtering.utils.ProblemSetup;
import edu.cmu.graphchi.toolkits.collaborative_filtering.utils.VertexDataCache;
import gov.sandia.cognition.math.matrix.VectorEntry;
import gov.sandia.cognition.math.matrix.mtj.SparseVector;
import gov.sandia.cognition.math.matrix.mtj.SparseVectorFactoryMTJ;

/**
 * This is the implementation of Factorization Machines using the SGD method
 * based on the algorithm given in the following paper:
 * "Steffen Rendle (2012): Factorization Machines with libFM, in ACM Trans. Intell. Syst. Technol., 3(3), May"
 * 
 * @author mayank
 *
 */

class LibFM_SGDParams extends ModelParameters  {
	public static final String LAMBDA_0_KEY = "lambda_0";
	public static final String LAMBDA_W_KEY = "lambda_w";
	public static final String LAMBDA_V_KEY = "lambda_v";
	public static final String NUM_LATENT_FACTORS_KEY = "num_latent_factors";
	public static final String ETA_KEY = "eta";
	public static final String INIT_DEV_KEY = "init_dev";
	
	// Number of iterations - Stopping condition. 
	//TODO: Note that may be we can have a better stopping condition based on change in training RMSE.  
	int maxIterations;
	
	//The standard deviation of the normal distribution to be used for initializing
	//the parameters of V.
	double init_dev;
	
	//Regularization Parameters
	double lambda_0;
	double lambda_w;
	double[] lambda_v;
	//Learn Rate
	double eta;
	
	//Parameters. (Should this be stored in memory or should it be stored in the vertex / edge?
	double w_0;		//0-way interactions
	double[] w;		//1-way interactions
	double[][] v;	//2-way interactions

	int D;			//Number of latent features 
	
	public LibFM_SGDParams(String id, Map<String, String> paramsMap) {
		super(id, paramsMap);
		
		setDefaults();
		
		//parseJsonParams();
	}
	
	private void parseJsonParams() {
		//TODO: parse stopping condition.
		
		if(this.paramsMap.get(NUM_LATENT_FACTORS_KEY) != null) {
			this.D = Integer.parseInt(this.paramsMap.get(NUM_LATENT_FACTORS_KEY));
		}
		if(this.paramsMap.get(LAMBDA_0_KEY) != null) {
			this.lambda_0 = Float.parseFloat(this.paramsMap.get(LAMBDA_0_KEY));
		}
		if(this.paramsMap.get(LAMBDA_W_KEY) != null) {
			this.lambda_w = Float.parseFloat(this.paramsMap.get(LAMBDA_W_KEY));
		}
		if(this.paramsMap.get(LAMBDA_V_KEY) != null) {
			float lam_v = Float.parseFloat(this.paramsMap.get(LAMBDA_V_KEY));
			for(int i = 0; i < this.D; i++) {
				this.lambda_v[i] = lam_v;
			}
		}
		if(this.paramsMap.get(ETA_KEY) != null) {
			this.eta = Float.parseFloat(this.paramsMap.get(ETA_KEY));
		}
		if(this.paramsMap.get(INIT_DEV_KEY) != null) {
			this.init_dev = Float.parseFloat(this.paramsMap.get(INIT_DEV_KEY));
		}
	}

	private void setDefaults() {
		this.maxIterations = 20;
		
		this.D = 8;
		this.lambda_0 = 0.15;
		this.lambda_w = 0.15;
		this.lambda_v = new double[this.D];
		for(int i = 0; i < this.D; i++) {
			this.lambda_v[i] = 0.15;
		}

		this.eta = 0.001;
		this.init_dev = 0.1;
		
	}
	
	public void initParameters(int numFeatures) {
		//Parameters. (Should this be stored in memory or should it be stored in the vertex / edge?
		this.w_0 = 0;									//0-way interactions
		this.w = new double[numFeatures];			//1-way interactions
		this.v = new double[numFeatures][this.D];	//2-way interactions
		
		NormalDistribution  dist = new NormalDistribution(0, 0.1);
		for(int j = 0; j < numFeatures; j++) {
			this.w[j] = 0;
			for(int f = 0; f < this.D; f++) {
				this.v[j][f] = dist.sample();
			}
		}
	}

	@Override
	public void serialize(String dir) {
		// TODO Auto-generated method stub
		
	}
	@Override
	public void deserialize(String file) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public double predict(int userId, int itemId, SparseVector userFeatures,
			SparseVector itemFeatures, SparseVector edgeFeatures,
			DataSetDescription datasetDesc) {
		SparseVector row = createAllFeatureVec(userId, itemId, 
				userFeatures, itemFeatures, datasetDesc);
		return predict(row, datasetDesc);
	}
	
	public double predict(SparseVector row, DataSetDescription datasetDesc) {
		//y = w0 +
		double estVal = this.w_0;
		
		double sumTwoWay = 0;
		Iterator<VectorEntry> it = row.iterator();
		while(it.hasNext()) {
			VectorEntry vec = it.next();
			int i = vec.getIndex();
			double xi = vec.getValue();
			double wi = this.w[i];
			double[] vi = this.v[i];  
			
			// wi*xi +
			estVal += wi*xi;
			
			Iterator<VectorEntry> it2 = row.iterator();
			while(it2.hasNext()) {
				vec = it2.next();
				int j = vec.getIndex();
				if(j <= i)
					continue;
				double xj = vec.getValue();
				double[] vj = this.v[j];
				double dotProd = 0;
				for(int f = 0; f < this.D; f++) {
					dotProd += vi[f]*vj[f];
				}
				
				// <vi, vj>*xi*xj +
				sumTwoWay += dotProd*xi*xj;
			}
		}
		estVal = estVal + sumTwoWay;

		return estVal;
	}
	
	public SparseVector createAllFeatureVec(int user, int item, SparseVector userFeatures, 
			SparseVector itemFeatures, DataSetDescription datasetDesc) {
		//Construct a row of the design matrix.
		int numTotalFeatures = getEdgeFeaturesBase(datasetDesc) + datasetDesc.getNumRatingFeatures(); 
		SparseVector allFeatures = (new SparseVectorFactoryMTJ()).createVector(numTotalFeatures);
		
		//Set feature representing an user.
		allFeatures.setElement(user, 1);
		//Set feature representing an item.
		allFeatures.setElement(item, 1);
		
		Iterator<VectorEntry> it;
		//Set features representing user attributes.
		if(userFeatures != null) {
			it = userFeatures.iterator();
			while(it.hasNext()) {
				VectorEntry feature = it.next();
				int featureIndex = getUserFeatureBase(datasetDesc) + feature.getIndex(); 
				allFeatures.setElement(featureIndex, feature.getValue());
			}
		}
		
		//Set features representing item attributes.
		if(itemFeatures != null) {
			it = itemFeatures.iterator();
			while(it.hasNext()) {
				VectorEntry feature = it.next();
				int featureIndex = getItemFeatureBase(datasetDesc) + feature.getIndex();
				allFeatures.setElement(featureIndex, feature.getValue());
			}
		}
		
		return allFeatures;
	}
	
	private int getUserBase(DataSetDescription datasetDesc){
		return 0;
	}
	
	private int getItemBase(DataSetDescription datasetDesc) {
		return getUserBase(datasetDesc) + datasetDesc.getNumUsers();
	}
	
	private int getUserFeatureBase(DataSetDescription datasetDesc) {
		return getItemBase(datasetDesc) + datasetDesc.getNumItems();
	}
	
	private int getItemFeatureBase(DataSetDescription datasetDesc) {
		return getUserFeatureBase(datasetDesc) + datasetDesc.getNumUserFeatures();
	}
	
	private int getEdgeFeaturesBase(DataSetDescription datasetDesc) {
		return getItemFeatureBase(datasetDesc) + datasetDesc.getNumItemFeatures();
	}
	
	@Override
	public int getEstimatedMemoryUsage(DataSetDescription datasetDesc) {
		// TODO Auto-generated method stub
		return 0;
	}
	
}

public class LibFM_SGD  implements RecommenderAlgorithm  {
	DataSetDescription datasetDesc;
	LibFM_SGDParams  params;
	
	//Contains data about user and item features. Currently this is held in memory.
	VertexDataCache vertexDataCache = null;
	
	int iterationNum;

	protected Logger logger = ChiLogger.getLogger("LibFM_SGD");
	
	//Train RMSE
	double train_rmse;
	
	public LibFM_SGD(DataSetDescription dataDesc, ModelParameters par) {
		this.params = (LibFM_SGDParams)par;
		this.datasetDesc = dataDesc;
		
		this.iterationNum = 0;
	}
	
	@Override
	public void update(ChiVertex<Integer, RatingEdge> vertex, GraphChiContext context) {
		if(vertex.numOutEdges() > 0) {
			//User vertex
			
			//Update user feature aggregates.
			int user = context.getVertexIdTranslate().backward(vertex.getId());
			//SparseVector userFeatures = this.features.getRow(user);
			
			SparseVector userFeatures = null;
			if(this.vertexDataCache != null) {
				userFeatures = this.vertexDataCache.getFeatures(user);
			}
			
			for(int e = 0; e < vertex.numOutEdges(); e++) {
				int item = context.getVertexIdTranslate().backward(vertex.getOutEdgeId(e));
				
				SparseVector itemFeatures = null;
				if(this.vertexDataCache != null) {
					itemFeatures = this.vertexDataCache.getFeatures(item);
				}
				
				RatingEdge edge = vertex.edge(e).getValue();

				SparseVector allFeatures = this.params.createAllFeatureVec(user, item, 
						userFeatures, itemFeatures, this.datasetDesc);
				
				//TODO: Set features representing edge attributes (like time stamp)
				double estVal = this.params.predict(allFeatures, this.datasetDesc);
				double err = edge.observation - estVal; 

				//Compute sum vj,f * xj for this observations
				Iterator<VectorEntry> it = allFeatures.iterator();
				double[] sum_v_x = new double[this.params.D];
				while(it.hasNext()) {
					VectorEntry vec = it.next();
					int j = vec.getIndex();
					double xj = vec.getValue();
					for(int f = 0; f < this.params.D; f++) {
						sum_v_x[f] += this.params.v[j][f]*xj;
					}
				}
				
				//Take a gradient step for the parameters.
				this.params.w_0 = this.params.w_0 - this.params.eta*(-2*err + 2*this.params.lambda_0*this.params.w_0);
				
				it = allFeatures.iterator();
				while(it.hasNext()) {
					VectorEntry vec = it.next();
					int j = vec.getIndex();
					double xj = vec.getValue();
					this.params.w[j] = this.params.w[j] - this.params.eta*(
							-2*err*xj + 2*this.params.lambda_w*this.params.w[j]);
					
					for(int f = 0; f < this.params.D; f++) {
						double old_vjf_x = this.params.v[j][f]*xj;
						this.params.v[j][f] = this.params.v[j][f] - this.params.eta*(
								-2*err *xj*(sum_v_x[f] - this.params.v[j][f]*xj) +
								2*this.params.lambda_v[f]*this.params.v[j][f]
							); 
						//TODO: Should this be updated?
						sum_v_x[f] += this.params.v[j][f]*xj - old_vjf_x;  
					}
				}
				estVal = Math.max(estVal, datasetDesc.getMinval());
				estVal = Math.min(estVal, datasetDesc.getMaxval());
				this.train_rmse += err*err; 
			}
				
				
		} else {
			//Item vertex
		}
	}

	@Override
	public void beginIteration(GraphChiContext ctx) {
		if(this.iterationNum == 0) {
			//On First iteration
			int numFeatures = this.datasetDesc.getNumItemFeatures() + 
					this.datasetDesc.getNumUserFeatures() + this.datasetDesc.getNumRatingFeatures();

			int numTotalFeatures = this.datasetDesc.getNumUsers() + this.datasetDesc.getNumItems()
					+ numFeatures;
			
			this.params.initParameters(numTotalFeatures);
		}
		
		this.train_rmse = 0;
		
	}

	@Override
	public void endIteration(GraphChiContext ctx) {
		
		this.train_rmse = Math.sqrt(this.train_rmse / (1.0 * ctx.getNumEdges()));
        this.logger.info("Train RMSE: " + this.train_rmse);
        
        this.iterationNum++;
	}

	@Override
	public void beginInterval(GraphChiContext ctx, VertexInterval interval) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void endInterval(GraphChiContext ctx, VertexInterval interval) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void beginSubInterval(GraphChiContext ctx, VertexInterval interval) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void endSubInterval(GraphChiContext ctx, VertexInterval interval) {
		// TODO Auto-generated method stub
	}
	
	@Override
	public ModelParameters getParams() {
		// TODO Auto-generated method stub
		return this.params;
	}

	@Override
	public boolean hasConverged(GraphChiContext ctx) {
		return this.iterationNum == this.params.maxIterations;
	}

	@Override
	public DataSetDescription getDataSetDescription() {
		// TODO Auto-generated method stub
		return this.datasetDesc;
	}
	
	@Override
	public int getEstimatedMemoryUsage() {
		// TODO Auto-generated method stub
		return 0;
	}
	
}

