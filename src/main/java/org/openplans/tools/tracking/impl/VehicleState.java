package org.openplans.tools.tracking.impl;

import gov.sandia.cognition.math.matrix.Matrix;
import gov.sandia.cognition.math.matrix.Vector;
import gov.sandia.cognition.math.matrix.VectorFactory;
import gov.sandia.cognition.statistics.ComputableDistribution;
import gov.sandia.cognition.statistics.ProbabilityFunction;
import gov.sandia.cognition.statistics.bayesian.AbstractKalmanFilter;
import gov.sandia.cognition.statistics.distribution.MultivariateGaussian;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;

import jj2000.j2k.NotImplementedError;

import org.openplans.tools.tracking.impl.InferredGraph.InferredEdge;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.vividsolutions.jts.geom.Coordinate;

/**
 * This class represents the state of a vehicle, which is made up of the
 * vehicles location, whether it is on an edge, which path it took from its
 * previous location on an edge, and the distributions that determine these.
 * 
 * @author bwillard
 * 
 */
public class VehicleState implements ComputableDistribution<VehicleStateConditionalParams> {

  public static class PDF extends VehicleState implements
      ProbabilityFunction<VehicleStateConditionalParams> {

    private static final long serialVersionUID = 879217079360170446L;

    public PDF(VehicleState state) {
      super(state);
    }

    @Override
    public PDF clone() {
      return (PDF) super.clone();
    }

    @Override
    public Double evaluate(VehicleStateConditionalParams input) {
      return Math.exp(logEvaluate(input));
    }

    @Override
    public VehicleState.PDF getProbabilityFunction() {
      return this;
    }

    @Override
    public double logEvaluate(VehicleStateConditionalParams input) {
      double logLikelihood = 0d;
      
      InferredEdge previousEdge = input.getPathEdge().getInferredEdge(); 
      /*
       * Edge transitions
       */
      logLikelihood += this.edgeTransitionDist.logEvaluate(previousEdge,
          this.getEdge());

      /*
       * Movement likelihood Note: should be predictive for PL
       */
      PathEdge edge;
      if (this.getEdge() == InferredGraph.getEmptyEdge()) {
        edge = PathEdge.getEmptyPathEdge(); 
      } else {
        edge = PathEdge.getEdge(this.getEdge(), input.getDistanceToCurrentEdge());
      }
      logLikelihood += this.getMovementFilter().logLikelihood(input.getLocation(), 
          this.belief, edge);


      return logLikelihood;
    }

    @Override
    public VehicleStateConditionalParams sample(Random random) {
      throw new NotImplementedError();
    }

    @Override
    public ArrayList<VehicleStateConditionalParams> sample(Random random, int numSamples) {
      throw new NotImplementedError();
    }

  }

  private static final long serialVersionUID = 3229140254421801273L;
  private static final double gVariance = 50d*50d/2d; // meters

  private static final double a0Variance = 0.5d; // m/s^2
  // TODO FIXME pretty sure this constant is being used in multiple places
  // for different things...
  private static final double aVariance = 0.9d; // m/s^2
  
  /*
   * These members represent the state/parameter samples/sufficient statistics.
   */
  private final StandardRoadTrackingFilter movementFilter;
  
  /**
   * This could be the 4D ground-coordinates dist. for free motion,
   * or the 2D road-coordinates, either way the tracking filter will check.
   */
  protected final MultivariateGaussian belief;

  /*-
   * Edge transition priors 
   * 1. edge off 
   * 2. edge on 
   * 3. edges transitions to others (one for all)
   * edges
   */
  protected final EdgeTransitionDistributions edgeTransitionDist;
  
  private final Observation observation;
  private final InferredEdge edge;
  private VehicleState parentState = null;
  private final Double distanceFromPreviousState;
  private final InferredPath path;

  public VehicleState(Observation initialObservation, InferredEdge inferredEdge) {
    Preconditions.checkNotNull(initialObservation);
    Preconditions.checkNotNull(inferredEdge);
    
    this.movementFilter = new StandardRoadTrackingFilter(
        gVariance, aVariance, a0Variance);
    
    if (inferredEdge == InferredGraph.getEmptyEdge()) {
      this.belief = movementFilter.getGroundFilter().createInitialLearnedObject();
      final Vector xyPoint = initialObservation.getProjectedPoint();
      belief.setMean(VectorFactory.getDefault().copyArray(
          new double[] { xyPoint.getElement(0), 0d, xyPoint.getElement(1), 0d }));
      
      this.path = InferredPath.getEmptyPath();
    } else {
      /*
       * Find our starting position on this edge
       */
      this.belief = movementFilter.getRoadFilter().createInitialLearnedObject();
      final Vector loc = inferredEdge.getPointOnEdge(initialObservation.getObsCoords());
      belief.setMean(VectorFactory.getDefault().copyArray(
          new double[] { inferredEdge.getStartPoint().euclideanDistance(loc), 0d}));
      
      this.path = new InferredPath(inferredEdge);
    }

    this.edge = inferredEdge;
    this.observation = initialObservation;
    this.edgeTransitionDist = new EdgeTransitionDistributions();
    this.distanceFromPreviousState = 0d; 
  }

  public VehicleState(VehicleState other) {
    this.movementFilter = other.movementFilter;
    this.belief = other.belief;
    this.edgeTransitionDist = other.edgeTransitionDist;
    this.edge = other.edge;
    this.observation = other.observation;
    this.distanceFromPreviousState = other.distanceFromPreviousState; 
    this.parentState = other.parentState;
    this.path = other.path;
  }

  public VehicleState(Observation observation,
    StandardRoadTrackingFilter filter, MultivariateGaussian belief,
    EdgeTransitionDistributions edgeTransitionDist,
    PathEdge edge, InferredPath path, VehicleState state) {
    this.observation = observation;
    this.movementFilter = filter;
    this.belief = belief;
    /*
     * This is the constructor used when creating transition states,
     * so this is where we'll need to reset the distance measures
     */
    this.distanceFromPreviousState = edge.getDistToStartOfEdge(); 
    if (belief.getInputDimensionality() == 2)
      belief.getMean().setElement(0, belief.getMean().getElement(0) - edge.getDistToStartOfEdge());
    this.edgeTransitionDist = edgeTransitionDist;
    this.edge = edge.getInferredEdge();
    this.parentState = state;
    this.path = path;
  }

  @Override
  public VehicleState clone() {
    try {
      return (VehicleState) super.clone();
    } catch (final CloneNotSupportedException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    return null;
  }

  public EdgeTransitionDistributions getEdgeTransitionDist() {
    return edgeTransitionDist;
  }

  /**
   * Returns ground-coordinate mean location
   * @return
   */
  public Vector getMeanLocation() {
    Vector v;
    if (belief.getInputDimensionality() == 2) {
      Preconditions.checkArgument(this.edge != InferredGraph.getEmptyEdge());
      Entry<Matrix, Vector> projPair = StandardRoadTrackingFilter.posVelProjectionPair(
          PathEdge.getEdge(this.edge, 0d));
      Vector truncatedMean; 
      if (belief.getMean().getElement(0) > this.edge.getLength()) {
        // TODO perhaps this is why we need truncated normals
        truncatedMean = belief.getMean().clone();
        truncatedMean.setElement(0, edge.getLength());
      } else {
        truncatedMean = belief.getMean();
      }
      v = projPair.getKey().times(belief.getMean()).plus(projPair.getValue());
    } else {
      v = belief.getMean();
    }
    return VectorFactory.getDefault().createVector2D(v.getElement(0), v.getElement(2));
  }
  
  @Override
  public VehicleState.PDF getProbabilityFunction() {
    return new VehicleState.PDF(this);
  }

  @Override
  public VehicleStateConditionalParams sample(Random random) {
    throw new NotImplementedError();
  }

  @Override
  public ArrayList<VehicleStateConditionalParams> sample(Random random, int numSamples) {
    throw new NotImplementedError();
  }

  public static double getAvariance() {
    return aVariance;
  }

  public static double getGvariance() {
    return gVariance;
  }

  public static long getSerialversionuid() {
    return serialVersionUID;
  }

  public Observation getObservation() {
    return observation;
  }

  public static double getA0variance() {
    return a0Variance;
  }

  public StandardRoadTrackingFilter getMovementFilter() {
    return movementFilter;
  }

  public MultivariateGaussian getBelief() {
    return belief;
  }

  @Override
  public String toString() {
    return "VehicleState [movementFilter=" + movementFilter + ", belief="
        + belief + ", edgeTransitionDist=" + edgeTransitionDist
        + ", observation=" + observation + ", edge=" + edge + ", parentState="
        + parentState + ", distanceFromPreviousState="
        + distanceFromPreviousState + "]";
  }

  public InferredEdge getEdge() {
    return this.edge;
  }

  public VehicleState getParentState() {
    return parentState;
  }

  public void setParentState(VehicleState parentState) {
    this.parentState = parentState;
  }

  public Double getDistanceFromPreviousState() {
    return distanceFromPreviousState;
  }

  /**
   * Gets the belief in ground coordinates (even if it's really tracking
   * road coordinates).
   * @return
   */
  public MultivariateGaussian getGroundOnlyBelief() {
    if (belief.getInputDimensionality() == 2) {
      Preconditions.checkArgument(this.edge != InferredGraph.getEmptyEdge());
      Entry<Matrix, Vector> projPair = StandardRoadTrackingFilter.posVelProjectionPair(
          PathEdge.getEdge(this.edge, 0d));
      Vector truncatedMean; 
      if (belief.getMean().getElement(0) > this.edge.getLength()) {
        // TODO perhaps this is why we need truncated normals
        truncatedMean = belief.getMean().clone();
        truncatedMean.setElement(0, edge.getLength());
      } else {
        truncatedMean = belief.getMean();
      }
      
      Matrix C = belief.getCovariance();
      MultivariateGaussian beliefProj = new MultivariateGaussian();
      beliefProj.setMean(projPair.getKey().transpose().times(truncatedMean.minus(projPair.getValue())));
      beliefProj.setCovariance(projPair.getKey().transpose().times(C).times(projPair.getKey()));
      return beliefProj;
    } else {
      return belief;
    }
  }

  public InferredPath getPath() {
    return path;
  }


}
