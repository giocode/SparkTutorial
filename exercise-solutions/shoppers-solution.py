#!/usr/bin/env python
import csv
import sys
import itertools
import StringIO

from operator import add
from os.path import join, isfile, dirname

from pyspark import SparkContext, SparkConf

# ------------------------------
#  Some local parser functions
#-------------------------------
def loadOffer(line):
  input = StringIO.StringIO(line)
  reader = csv.DictReader(input, fieldnames = ["offer_id", "category","quantity","company","offervalue","brand"])    
  return reader.next()

def convertTypeOffer(o):
  o['quantity'] = int(o['quantity']) 
  o['offervalue'] = float(o['offervalue'])
  return o

def loadHistory(line):
  input = StringIO.StringIO(line)
  reader = csv.DictReader(input, fieldnames = ["customer_id", "chain","offer_id","market","repeattrips","repeater", "offerdate"])
  return reader.next()

def convertTypeHistory(h):
  h['repeattrips'] = int(h['repeattrips'])
  h['chain'] = int(h['chain'])
  h['repeater'] = {'t': True}.get(h['repeater'], False)  
  return h

def loadTransaction(line):
  input = StringIO.StringIO(line)
  reader = csv.DictReader(input, fieldnames = ["customer_id", "chain","dept","category","company","brand","date","productsize","productmeasure","purchasequantity","purchaseamount"])    
  return reader.next()

def convertTypeTransaction(t):
  t['chain'] = int(t['chain'])
  t['productsize'] = float(t['productsize'])
  t['purchasequantity'] = int(t['purchasequantity'])
  t['purchaseamount'] = float(t['purchaseamount'])
  return t

#-----------------------------------------


if __name__ == "__main__":


  


  conf = SparkConf().setAppName("ShoppersApp").set("spark.executor.memory","1g")
  sc = SparkContext(conf = conf)

  # Load the files into RDDs of History, Offer and Transaction dictionnaries
  history = sc.textFile("../data/shoppers/history").map(loadHistory).map(convertTypeHistory)
  offers = sc.textFile("../data/shoppers/offers").map(loadOffer).map(convertTypeOffer)


  # ----------------------------------- YOUR CODE FOLLOWS HERE ------------------------------------- 

  # Q1: Create RDDs of key-value pairs for 'history' and 'offers' and join them into histOffers Pair RDD
  def byOffer_Id(x): 
    return (x['offer_id'], x) 
  histOffers = (history.map(byOffer_Id)).join(offers.map(byOffer_Id))

  # Q2: Find the popular offers that resulted in 5 most repeattrips and return a collection of 3-tuple (customer_id, offer_id, repeattrips)
  top5Offers = histOffers.groupBy(lambda elmt: elmt[1][0]['repeattrips']) \
                         .sortByKey(False).flatMap(lambda t: t[1]) \
                         .map(lambda elmt: (elmt[1][0]['customer_id'], elmt[0], elmt[1][0]['repeattrips'], elmt[1][1]['offervalue'])) \
                         .take(5)

  # Then print these Top 5 Offers
  for x in top5Offers:
    print("Customer #" + str(x[0]) + " saved " + str(x[3]) + " dollars on offer #" + str(x[1]) + " and later made repeteated purchases " + str(x[2]) + " times")

    # Should print: 
    #         Customer #3465135195 saved 0.75 dollars on offer #1197502 and later made repeteated purchases 2124 times
    #         Customer #3450535153 saved 0.75 dollars on offer #1197502 and later made repeteated purchases 1418 times
    #         Customer #4427711419 saved 1.0 dollars on offer #1203052 and later made repeteated purchases 549 times
    #         Customer #4176323168 saved 1.0 dollars on offer #1203052 and later made repeteated purchases 104 times
    #         Customer #239276354 saved 0.75 dollars on offer #1197502 and later made repeteated purchases 87 times

  # Extract top 5 customers
  top5Customers = [x[0] for x in top5Offers]

  # // Q3: find the maximum number of repeattrips generated by offers with a 5$ discount value
  offersFiveBucks = histOffers.filter(lambda elmt: elmt[1][1] ['offervalue'] == 3) \
                              .groupBy (lambda elmt: elmt[1][0]['repeattrips']) \
                              .sortByKey(False).first()
                              
  maxRepeatsFromFiveBucks = offersFiveBucks[0]
  print("\nCoupons with 5 dollar value yield a maximum of only " + str(maxRepeatsFromFiveBucks) + " repeated purchases")

  # Let us now look into the purchase history of these top 5 Customers prior to their offered incentive

  # The dataset containing the transactions of these top 5 customers are given to you as textfiles in topTransactions folder 
  # Let us first load into RDD 

  transactions = sc.textFile("../data/shoppers/topTransactions", 100).map(loadTransaction).map(convertTypeTransaction)


  # RDD of case objects of type Transaction

  # Q4: Compute the average spending Per purchase trip (assuming one trip per day) of these 5 customers

  # Map the transactions by Customer and Date, i.e. the key consists of a pair (customer_id, date)
  transKeyedByCustomerAndDate = transactions.map(lambda t : ((t['customer_id'], t['date']), t))

  # Compute the total spending per trip per customer using combineByKey or reduceByKey
  spendingPerTrip = transKeyedByCustomerAndDate.combineByKey(
          (lambda t : t['purchaseamount']), 
          (lambda acc, t : acc + t['purchaseamount']),
          (lambda a1, a2 : a1 + a2))

  # Transform spendingPerTrip into an RDD of pairs (customer_id, totalSpendingValue) for each trip 
  spendingsPerCustomer = spendingPerTrip.map(lambda x:  (x[0][0], x[1]))

  # Compute the average spending per trip for each customer
  avgSpendingPerTrip = spendingsPerCustomer.mapValues(lambda s: (s,1)) \
                                           .reduceByKey(lambda a, b : (a[0] + b[0], a[1] + b[1])) \
                                           .mapValues(lambda t: t[0] / t[1])  \
                                           .collect()
                                           
                                           
  # Print the average spending per trip for each customer
  print(" ")
  for (customer, avg) in avgSpendingPerTrip:
   print("Customer #" + customer + " : " + str(avg))

  # Bonus Question: For each customer, compute the average spending Per Purchase trip only for items that have  
  # the same Category as the one for which they were offered a coupon, e.g. same category in transactions and history. 
  
  # clean up
  sc.stop()

