# Results notes appliying DBC in BlockHeader

The next precondittions were added according to http://paper.gavwood.com/

* Adding precondition 256-bit hash length for parentHash
``` 
result: Total 1066, Failed 212, Errors 0, Passed 854
```

* Adding precondition 256-bit hash length for ommersHash
``` 
result: Total 1066, Failed 213, Errors 0, Passed 853
```

* Adding precondition 160-bit length for beneficiary
``` 
result: Total 1066, Failed 213, Errors 0, Passed 853
```

* Adding precondition 256-bit hash length for stateRoot
``` 
result: Total 1066, Failed 215, Errors 0, Passed 851
``` 

* Adding precondition 256-bit hash length for transactionsRoot
```
result: Total 1066, Failed 214, Errors 0, Passed 852
```

* Adding precondition 256-bit hash length for receiptsRoot
```
result: Total 1066, Failed 214, Errors 0, Passed 852
```

* Adding precondition more than or equal to 0 for difficulty
```
result: Total 1066, Failed 0, Errors 0, Passed 1066
```

* Adding precondition more than or equal to 0 for number
```
result: Total 1066, Failed 1, Errors 0, Passed 1065
```

* Adding precondition more than or equal to 5000 and less than 2^63-1 for gasLimit
```
result: Total 1066, Failed 82, Errors 0, Passed 984
```
* Adding precondition less than or equal to gasLimit for gasUsed
```
result: Total 1066, Failed 3, Errors 0, Passed 1063
```
* Adding precondition 32 bytes or fewer for extraData
```
result: Total 1066, Failed 1, Errors 0, Passed 1065
```
* Adding precondition 256-bit hash length for mixHash
```
Total 1066, Failed 222, Errors 0, Passed 844
```
* Adding precondition 64-bit hash length for nonce
```
Total 1066, Failed 221, Errors 0, Passed 845
```